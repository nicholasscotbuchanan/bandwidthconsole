//! DPDK kernel-bypass datapath, used two ways:
//!
//!  * **UDP+DPDK** — raw datagram blasting straight over a poll-mode driver,
//!    with the same receiver-feedback scheme the kernel UDP engine uses so the
//!    numbers are comparable.
//!  * **QUIC+DPDK** — the full quinn QUIC stack (TLS 1.3, streams, loss
//!    recovery) riding on DPDK instead of kernel UDP sockets. quinn abstracts
//!    its transport behind [`AsyncUdpSocket`], so [`DpdkUdpSocket`] is the whole
//!    integration: implement it and QUIC runs kernel-bypass unchanged.
//!
//! The packet plumbing (EAL, mbufs, rx/tx burst, Ethernet/IPv4/UDP framing)
//! lives in [`crate::dpdkrt`]. Everything here is compiled on every platform;
//! without the `dpdk` feature the runtime returns a clear error instead.

use std::fmt;
use std::io::{self, IoSliceMut};
use std::net::SocketAddr;
use std::pin::Pin;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use std::task::{Context, Poll};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use anyhow::{Context as _, Result};
use quinn::udp::{RecvMeta, Transmit};
use quinn::{AsyncUdpSocket, UdpPoller};

use super::{sample_loop, Counters, PhaseTimer, Stop, Tx};
use crate::dpdkrt::{self, DpdkPort};
use crate::protocol::Scenario;

/// True when this build+host can drive DPDK at all (feature + Linux).
pub fn available() -> bool {
    cfg!(all(feature = "dpdk", target_os = "linux"))
}

pub fn unavailable_reason() -> String {
    if !cfg!(target_os = "linux") {
        "DPDK is Linux-only; this agent's host does not support it".into()
    } else if !cfg!(feature = "dpdk") {
        "this agent was built without the `dpdk` feature; rebuild with \
         --features dpdk on a DPDK-provisioned Linux host"
            .into()
    } else {
        "DPDK is compiled in but not configured; pass --dpdk-eal and --dpdk-ip".into()
    }
}

/// Wait for the link to come up (memif reports down until its peer attaches).
pub async fn await_link(timeout: Duration) -> Result<()> {
    let start = Instant::now();
    while start.elapsed() < timeout {
        if dpdkrt::link_up() {
            return Ok(());
        }
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
    // Not fatal: some PMDs never report link state. Proceed and let I/O decide.
    tracing::warn!("DPDK link did not report up within {timeout:?}; continuing");
    Ok(())
}

// ------------------------------------------------------------- quinn socket

/// A quinn transport backed by a DPDK poll-mode driver instead of a kernel
/// socket. This is the entire QUIC-over-DPDK integration point.
pub struct DpdkUdpSocket {
    port: DpdkPort,
}

impl fmt::Debug for DpdkUdpSocket {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "DpdkUdpSocket({})", self.port.local)
    }
}

impl DpdkUdpSocket {
    /// Bring the datapath up and bind a UDP port on it.
    pub fn bind(addr: SocketAddr) -> io::Result<Arc<Self>> {
        dpdkrt::start()?;
        let port = dpdkrt::bind(addr.port())?;
        tracing::info!(local = %port.local, "QUIC over DPDK bound");
        Ok(Arc::new(Self { port }))
    }
}

impl AsyncUdpSocket for DpdkUdpSocket {
    fn create_io_poller(self: Arc<Self>) -> Pin<Box<dyn UdpPoller>> {
        // The PMD tx ring drains continuously; when it is momentarily full
        // `try_send` reports WouldBlock and quinn retries on the next poll.
        Box::pin(AlwaysWritable)
    }

    fn try_send(&self, transmit: &Transmit) -> io::Result<()> {
        self.port.send_to(transmit.destination, transmit.contents)
    }

    fn poll_recv(
        &self,
        cx: &mut Context,
        bufs: &mut [IoSliceMut<'_>],
        meta: &mut [RecvMeta],
    ) -> Poll<io::Result<usize>> {
        let mut n = 0;
        while n < bufs.len() && n < meta.len() {
            match self.port.try_recv() {
                Some((src, payload)) => {
                    let len = payload.len().min(bufs[n].len());
                    bufs[n][..len].copy_from_slice(&payload[..len]);
                    meta[n] = RecvMeta {
                        addr: src,
                        len,
                        stride: len,
                        ecn: None,
                        dst_ip: None,
                    };
                    n += 1;
                }
                None => break,
            }
        }
        if n > 0 {
            return Poll::Ready(Ok(n));
        }
        // Nothing queued: park until the rx thread delivers something.
        self.port.register(cx.waker().clone());
        // Re-check to close the race between the empty read and registering.
        if let Some((src, payload)) = self.port.try_recv() {
            let len = payload.len().min(bufs[0].len());
            bufs[0][..len].copy_from_slice(&payload[..len]);
            meta[0] = RecvMeta {
                addr: src,
                len,
                stride: len,
                ecn: None,
                dst_ip: None,
            };
            return Poll::Ready(Ok(1));
        }
        Poll::Pending
    }

    fn local_addr(&self) -> io::Result<SocketAddr> {
        Ok(self.port.local)
    }

    fn max_receive_segments(&self) -> usize {
        32
    }

    fn may_fragment(&self) -> bool {
        false // we build the frames; nothing fragments
    }
}

#[derive(Debug)]
struct AlwaysWritable;

impl UdpPoller for AlwaysWritable {
    fn poll_writable(self: Pin<&mut Self>, _cx: &mut Context) -> Poll<io::Result<()>> {
        Poll::Ready(Ok(()))
    }
}

// --------------------------------------------------- raw UDP over DPDK

// Wire format matches the kernel UDP engine so results are comparable:
//   data:     [send_ts:8][seq:8][padding…]
//   feedback: [bytes:8][pkts:8][echo_ts:8][high_seq:8]
const HDR: usize = 16;
const FB: usize = 32;

fn now_nanos() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos() as u64
}

pub async fn run_send(
    run_id: String,
    sc: Scenario,
    target_addr: String,
    tx: Tx,
    stop: Stop,
) -> Result<crate::protocol::RunSummary> {
    dpdkrt::start().context("DPDK start")?;
    await_link(Duration::from_secs(10)).await?;

    let target: SocketAddr = super::resolve(&target_addr)?;
    let streams = sc.threads.max(1) as usize;
    let counters = Counters::new(streams);
    let mut timer = PhaseTimer::start();
    let first_rtt = Arc::new(AtomicU64::new(0));
    let payload_len = (sc.payload_bytes as usize).clamp(HDR, dpdkrt::max_payload());

    timer.connect_ms = timer.elapsed_ms();

    let mut handles = Vec::new();
    for idx in 0..streams {
        let port = dpdkrt::bind(0)?;
        let (c, s, fr) = (counters.clone(), stop.clone(), first_rtt.clone());
        handles.push(std::thread::spawn(move || {
            let mut buf = vec![0xCDu8; payload_len];
            let mut seq: u64 = 0;
            let (mut last_bytes, mut last_pkts) = (0u64, 0u64);
            while !s.load(Ordering::Relaxed) {
                buf[0..8].copy_from_slice(&now_nanos().to_le_bytes());
                buf[8..16].copy_from_slice(&seq.to_le_bytes());
                match port.send_to(target, &buf) {
                    Ok(()) => seq += 1,
                    Err(ref e) if e.kind() == io::ErrorKind::WouldBlock => {}
                    Err(_) => break,
                }
                // Absorb any feedback the receiver has sent back.
                while let Some((_, fb)) = port.try_recv() {
                    if fb.len() >= FB {
                        let d_bytes = u64::from_le_bytes(fb[0..8].try_into().unwrap());
                        let d_pkts = u64::from_le_bytes(fb[8..16].try_into().unwrap());
                        let echo_ts = u64::from_le_bytes(fb[16..24].try_into().unwrap());
                        if d_bytes >= last_bytes {
                            let db = d_bytes - last_bytes;
                            c.bytes.fetch_add(db, Ordering::Relaxed);
                            if let Some(sl) = c.streams.get(idx) {
                                sl.fetch_add(db, Ordering::Relaxed);
                            }
                            last_bytes = d_bytes;
                        }
                        if d_pkts >= last_pkts {
                            c.packets.fetch_add(d_pkts - last_pkts, Ordering::Relaxed);
                            last_pkts = d_pkts;
                        }
                        c.retransmits.store(seq.saturating_sub(d_pkts), Ordering::Relaxed);
                        let rtt = now_nanos().saturating_sub(echo_ts) / 1000;
                        if rtt > 0 && rtt < 60_000_000 {
                            c.rtt_us.store(rtt, Ordering::Relaxed);
                            let _ = fr.compare_exchange(0, rtt, Ordering::Relaxed, Ordering::Relaxed);
                        }
                    }
                }
            }
        }));
    }
    timer.handshake_ms = timer.elapsed_ms();

    for _ in 0..200 {
        if first_rtt.load(Ordering::Relaxed) > 0 {
            break;
        }
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    timer.first_byte_ms = first_rtt.load(Ordering::Relaxed) as f64 / 1000.0;

    let (peak, avg, mut rtts) = sample_loop(
        run_id.clone(), "send", counters.clone(), stop.clone(), tx.clone(),
        sc.duration_secs, sc.bytes_target, sc.continuous,
    )
    .await;

    let teardown = Instant::now();
    stop.store(true, Ordering::Relaxed);
    for h in handles {
        let _ = h.join();
    }
    timer.teardown_ms = teardown.elapsed().as_secs_f64() * 1000.0;
    timer.ramp_ms = rtts.first().copied().unwrap_or(1.0).max(10.0);
    timer.steady_ms = (sc.duration_secs as f64 * 1000.0 - timer.ramp_ms).max(0.0);

    let retransmits = counters.retransmits.load(Ordering::Relaxed);
    let bytes = counters.bytes.load(Ordering::Relaxed);
    Ok(timer.finish(retransmits, false, &run_id, peak, avg, bytes, &mut rtts))
}

pub async fn run_recv(run_id: String, sc: Scenario, tx: Tx) -> Result<(SocketAddr, Stop)> {
    dpdkrt::start().context("DPDK start")?;
    let port = dpdkrt::bind(0)?;
    let local = port.local;
    let stop: Stop = Arc::new(AtomicBool::new(false));
    // Receiver-side goodput accounting, same shape as the kernel-UDP receiver.
    let counters = Counters::new(sc.threads.max(1) as usize);
    super::spawn_recv_sampler(run_id, counters.clone(), stop.clone(), tx);
    let stop2 = stop.clone();

    std::thread::spawn(move || {
        use std::collections::HashMap;
        #[derive(Default, Clone)]
        struct Tally {
            bytes: u64,
            pkts: u64,
            high_seq: u64,
            last_ts: u64,
        }
        let mut peers: HashMap<SocketAddr, Tally> = HashMap::new();
        let mut peer_idx: HashMap<SocketAddr, usize> = HashMap::new();
        let mut last_flush = Instant::now();
        while !stop2.load(Ordering::Relaxed) {
            let mut idle = true;
            // Bounded burst: at DPDK ingress rates an unbounded drain never
            // returns, so the 250ms feedback flush below would never run and the
            // sender's counters (which are fed only by that feedback) stay zero.
            let mut budget = 512;
            while let Some((from, data)) = port.try_recv() {
                idle = false;
                budget -= 1;
                if data.len() >= HDR {
                    let n_peers = peer_idx.len();
                    let idx = *peer_idx.entry(from)
                        .or_insert(n_peers % counters.streams.len());
                    counters.add(idx, data.len() as u64);
                    let t = peers.entry(from).or_default();
                    t.bytes += data.len() as u64;
                    t.pkts += 1;
                    t.last_ts = u64::from_le_bytes(data[0..8].try_into().unwrap());
                    let seq = u64::from_le_bytes(data[8..16].try_into().unwrap());
                    t.high_seq = t.high_seq.max(seq);
                }
                if budget == 0 {
                    break;
                }
            }
            if last_flush.elapsed() >= Duration::from_millis(250) {
                for (addr, t) in peers.iter() {
                    let mut fb = [0u8; FB];
                    fb[0..8].copy_from_slice(&t.bytes.to_le_bytes());
                    fb[8..16].copy_from_slice(&t.pkts.to_le_bytes());
                    fb[16..24].copy_from_slice(&t.last_ts.to_le_bytes());
                    fb[24..32].copy_from_slice(&t.high_seq.to_le_bytes());
                    let _ = port.send_to(*addr, &fb);
                }
                last_flush = Instant::now();
            }
            if idle {
                std::thread::sleep(Duration::from_micros(50));
            }
        }
    });

    Ok((local, stop))
}

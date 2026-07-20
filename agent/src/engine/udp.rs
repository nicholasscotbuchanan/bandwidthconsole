//! UDP data plane with lightweight receiver feedback.
//!
//! Every datagram carries `[send_ts:8][seq:8][payload…]`. The sink tallies each
//! source address and, four times a second, mails back
//! `[bytes_cum:8][pkts_cum:8][echo_ts:8][high_seq:8]`. That feedback lets the
//! source report *delivered* goodput (not just offered load), packet loss, and a
//! real RTT proxy — so UDP numbers are as honest as the TCP ones.

use std::collections::HashMap;
use std::net::{SocketAddr, UdpSocket};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use anyhow::Result;
use socket2::{Domain, Protocol as L4, Socket, Type};

use super::{sample_loop, Counters, PhaseTimer, Stop, Tx};
use crate::protocol::Scenario;

const HDR: usize = 16; // send_ts(8) + seq(8)
const FB: usize = 32; // feedback datagram length

fn now_nanos() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos() as u64
}

fn make_source_socket(target: SocketAddr, sc: &Scenario) -> Result<UdpSocket> {
    let sock = Socket::new(Domain::for_address(target), Type::DGRAM, Some(L4::UDP))?;
    super::apply_dscp(&sock, sc)?;
    sock.set_send_buffer_size(4 * 1024 * 1024).ok();
    sock.set_recv_buffer_size(4 * 1024 * 1024).ok();
    sock.bind(&"0.0.0.0:0".parse::<SocketAddr>().unwrap().into())?;
    sock.set_nonblocking(true)?;
    Ok(sock.into())
}

/// One source stream: blast datagrams, absorb feedback, keep the shared counters
/// reflecting *delivered* bytes/packets/loss/RTT.
fn source_stream(
    sock: UdpSocket,
    target: SocketAddr,
    payload_len: usize,
    target_mbps: u32,
    idx: usize,
    counters: Arc<Counters>,
    stop: Stop,
    first_rtt: Arc<AtomicU64>,
) {
    let dlen = payload_len.max(HDR);
    let mut buf = vec![0xCDu8; dlen];
    let mut fbbuf = [0u8; 64];
    let mut seq: u64 = 0;
    let mut last_bytes = 0u64;
    let mut last_pkts = 0u64;

    // Token-bucket pacing when a target rate is set (per-stream share is applied
    // by the caller dividing target_mbps across streams).
    let bytes_per_sec = if target_mbps > 0 {
        (target_mbps as f64 * 1.0e6 / 8.0) as u64
    } else {
        u64::MAX
    };
    let mut window_start = Instant::now();
    let mut window_bytes = 0u64;

    while !stop.load(Ordering::Relaxed) {
        // Pace.
        if bytes_per_sec != u64::MAX {
            let elapsed = window_start.elapsed().as_secs_f64();
            if elapsed >= 1.0 {
                window_start = Instant::now();
                window_bytes = 0;
            } else if window_bytes >= bytes_per_sec {
                std::thread::sleep(Duration::from_millis(1));
                continue;
            }
        }

        buf[0..8].copy_from_slice(&now_nanos().to_le_bytes());
        buf[8..16].copy_from_slice(&seq.to_le_bytes());
        match sock.send_to(&buf, target) {
            Ok(n) => {
                seq += 1;
                window_bytes += n as u64;
            }
            Err(ref e) if e.kind() == std::io::ErrorKind::WouldBlock => {}
            Err(_) => break,
        }

        // Drain any feedback (non-blocking).
        while let Ok(n) = sock.recv(&mut fbbuf) {
            if n >= FB {
                let d_bytes = u64::from_le_bytes(fbbuf[0..8].try_into().unwrap());
                let d_pkts = u64::from_le_bytes(fbbuf[8..16].try_into().unwrap());
                let echo_ts = u64::from_le_bytes(fbbuf[16..24].try_into().unwrap());
                // Delivered deltas → shared counters (aggregate + this stream).
                if d_bytes >= last_bytes {
                    let db = d_bytes - last_bytes;
                    counters.bytes.fetch_add(db, Ordering::Relaxed);
                    if let Some(s) = counters.streams.get(idx) {
                        s.fetch_add(db, Ordering::Relaxed);
                    }
                    last_bytes = d_bytes;
                }
                if d_pkts >= last_pkts {
                    counters.packets.fetch_add(d_pkts - last_pkts, Ordering::Relaxed);
                    last_pkts = d_pkts;
                }
                // Loss = sent - delivered (monotonic, clamp).
                let lost = seq.saturating_sub(d_pkts);
                counters.retransmits.store(lost, Ordering::Relaxed);
                // RTT proxy: time since the send that the sink last acknowledged.
                let rtt = now_nanos().saturating_sub(echo_ts) / 1000;
                if rtt > 0 && rtt < 60_000_000 {
                    counters.rtt_us.store(rtt, Ordering::Relaxed);
                    let _ = first_rtt.compare_exchange(0, rtt, Ordering::Relaxed, Ordering::Relaxed);
                }
            }
        }
    }
}

pub async fn run_source(
    run_id: String,
    sc: Scenario,
    target_addr: String,
    tx: Tx,
    stop: Stop,
) -> Result<crate::protocol::RunSummary> {
    let target: SocketAddr = super::resolve(&target_addr)?;
    let threads = sc.threads.max(1);
    let counters = Counters::new(threads as usize);
    let mut timer = PhaseTimer::start();
    let first_rtt = Arc::new(AtomicU64::new(0));
    let per_stream_mbps = if sc.target_mbps > 0 {
        (sc.target_mbps / threads).max(1)
    } else {
        0
    };

    timer.connect_ms = timer.elapsed_ms();
    let mut handles = Vec::new();
    for idx in 0..threads as usize {
        let sock = make_source_socket(target, &sc)?;
        let (c, s, fr) = (counters.clone(), stop.clone(), first_rtt.clone());
        let plen = sc.payload_bytes.max(HDR as u32) as usize;
        // Threaded and Selector both spin a stream loop; UDP has no accept/reactor
        // asymmetry to exploit, so the honest difference is only thread count.
        handles.push(std::thread::spawn(move || {
            source_stream(sock, target, plen, per_stream_mbps, idx, c, s, fr)
        }));
    }
    timer.handshake_ms = timer.elapsed_ms();

    for _ in 0..100 {
        if first_rtt.load(Ordering::Relaxed) > 0 {
            break;
        }
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    timer.first_byte_ms = first_rtt.load(Ordering::Relaxed) as f64 / 1000.0;

    let (peak, avg, mut rtts) = sample_loop(
        run_id.clone(), "source", counters.clone(), stop.clone(), tx.clone(),
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
    // UDP has no kernel SACK; report false.
    Ok(timer.finish(retransmits, false, &run_id, peak, avg, bytes, &mut rtts))
}

/// Sink: one socket, per-source tallies, feedback flushed 4×/second.
pub async fn run_sink(run_id: String, sc: Scenario, tx: Tx) -> Result<(SocketAddr, Stop)> {
    let sock = Socket::new(Domain::IPV4, Type::DGRAM, Some(L4::UDP))?;
    sock.set_reuse_address(true)?;
    super::apply_dscp(&sock, &sc)?;
    sock.set_recv_buffer_size(8 * 1024 * 1024).ok();
    sock.bind(&"0.0.0.0:0".parse::<SocketAddr>().unwrap().into())?;
    let sock: UdpSocket = sock.into();
    let local = sock.local_addr()?;
    sock.set_read_timeout(Some(Duration::from_millis(50)))?;

    let stop: Stop = Arc::new(AtomicBool::new(false));
    // Sink-side goodput accounting: each source stream is its own socket, so
    // peers map 1:1 to streams; slots are claimed in arrival order.
    let counters = Counters::new(sc.threads.max(1) as usize);
    super::spawn_sink_sampler(run_id, counters.clone(), stop.clone(), tx);
    let stop2 = stop.clone();
    std::thread::spawn(move || {
        #[derive(Default, Clone)]
        struct Tally {
            bytes: u64,
            pkts: u64,
            high_seq: u64,
            last_ts: u64,
        }
        let mut peers: HashMap<SocketAddr, Tally> = HashMap::new();
        let mut peer_idx: HashMap<SocketAddr, usize> = HashMap::new();
        let mut buf = vec![0u8; 2 * 1024 * 1024];
        let mut last_flush = Instant::now();
        while !stop2.load(Ordering::Relaxed) {
            match sock.recv_from(&mut buf) {
                Ok((n, from)) if n >= HDR => {
                    let n_peers = peer_idx.len();
                    let idx = *peer_idx.entry(from)
                        .or_insert(n_peers % counters.streams.len());
                    counters.add(idx, n as u64);
                    let t = peers.entry(from).or_default();
                    t.bytes += n as u64;
                    t.pkts += 1;
                    t.last_ts = u64::from_le_bytes(buf[0..8].try_into().unwrap());
                    let seq = u64::from_le_bytes(buf[8..16].try_into().unwrap());
                    t.high_seq = t.high_seq.max(seq);
                }
                _ => {}
            }
            if last_flush.elapsed() >= Duration::from_millis(250) {
                for (addr, t) in peers.iter() {
                    let mut fb = [0u8; FB];
                    fb[0..8].copy_from_slice(&t.bytes.to_le_bytes());
                    fb[8..16].copy_from_slice(&t.pkts.to_le_bytes());
                    fb[16..24].copy_from_slice(&t.last_ts.to_le_bytes());
                    fb[24..32].copy_from_slice(&t.high_seq.to_le_bytes());
                    let _ = sock.send_to(&fb, addr);
                }
                last_flush = Instant::now();
            }
        }
    });
    Ok((local, stop))
}

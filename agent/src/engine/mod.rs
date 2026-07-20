//! The data plane. A sender agent generates load against a receiver agent; both are
//! this same binary wearing different hats for a given run.
//!
//! Design goals that shaped this module:
//!  * Every number reported is *measured*, never fabricated — RTT comes from real
//!    application-level probes, goodput from bytes the socket actually moved.
//!  * The Threaded vs Selector split is a genuine architectural fork (OS threads +
//!    blocking I/O vs one async reactor), because telling the two apart is the
//!    whole point of the tool.

pub mod tcp;
pub mod udp;
pub mod dpdk;
pub mod quic;
pub mod frame;

use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Instant;

use anyhow::{Context, Result};
use socket2::Socket;
use tokio::sync::mpsc;

use crate::protocol::{
    Architecture, LatencyPhases, Protocol, RunSummary, Scenario, TelemetrySample,
};

/// Live counters shared between every worker (thread or task) and the sampler.
pub struct Counters {
    pub bytes: AtomicU64,
    pub packets: AtomicU64,
    /// TCP retransmits (Linux TCP_INFO) or UDP datagrams the receiver reported missing.
    pub retransmits: AtomicU64,
    /// Last measured round-trip in microseconds, published by the probe channel.
    pub rtt_us: AtomicU64,
    /// Cumulative bytes per stream, so the console can chart each stream's goodput.
    pub streams: Vec<AtomicU64>,
}

impl Counters {
    /// Create counters sized for `n` streams.
    pub fn new(n: usize) -> Arc<Self> {
        let mut streams = Vec::with_capacity(n.max(1));
        for _ in 0..n.max(1) {
            streams.push(AtomicU64::new(0));
        }
        Arc::new(Self {
            bytes: AtomicU64::new(0),
            packets: AtomicU64::new(0),
            retransmits: AtomicU64::new(0),
            rtt_us: AtomicU64::new(0),
            streams,
        })
    }

    /// Record `n` bytes moved on stream `idx` (updates aggregate + per-stream).
    pub fn add(&self, idx: usize, n: u64) {
        self.bytes.fetch_add(n, Ordering::Relaxed);
        self.packets.fetch_add(1, Ordering::Relaxed);
        if let Some(s) = self.streams.get(idx) {
            s.fetch_add(n, Ordering::Relaxed);
        }
    }
}

/// A flag every worker polls to know when the run window is over.
pub type Stop = Arc<AtomicBool>;

/// Channel the engine uses to push messages back toward the console writer.
pub type Tx = mpsc::UnboundedSender<crate::protocol::AgentMsg>;

/// Apply the DSCP code point to a socket as IP_TOS (dscp occupies the top 6
/// bits). Silently succeeds as a no-op path when disabled.
pub fn apply_dscp(sock: &Socket, sc: &Scenario) -> Result<()> {
    if sc.dscp_enabled {
        // IP_TOS = DSCP << 2 (the low 2 bits are ECN, left zero here).
        sock.set_tos((sc.dscp as u32) << 2)?;
    }
    Ok(())
}

/// Common TCP socket tuning shared by sender and receiver.
pub fn tune_tcp(sock: &Socket, sc: &Scenario) -> Result<()> {
    sock.set_nodelay(true)?;
    sock.set_reuse_address(true)?;
    apply_dscp(sock, sc)?;
    Ok(())
}

/// Records wall-clock phase boundaries during a run and renders them into the
/// Gantt-ready `LatencyPhases` (all values milliseconds).
pub struct PhaseTimer {
    start: Instant,
    pub connect_ms: f64,
    pub handshake_ms: f64,
    pub first_byte_ms: f64,
    pub ramp_ms: f64,
    pub steady_ms: f64,
    pub teardown_ms: f64,
}

impl PhaseTimer {
    pub fn start() -> Self {
        Self {
            start: Instant::now(),
            connect_ms: 0.0,
            handshake_ms: 0.0,
            first_byte_ms: 0.0,
            ramp_ms: 0.0,
            steady_ms: 0.0,
            teardown_ms: 0.0,
        }
    }
    pub fn elapsed_ms(&self) -> f64 {
        self.start.elapsed().as_secs_f64() * 1000.0
    }
    pub fn finish(&self, retransmits: u64, sack: bool, run_id: &str, peak: f64, avg: f64,
                  bytes: u64, rtts: &mut Vec<f64>) -> RunSummary {
        let (p50, p95, p99) = percentiles(rtts);
        RunSummary {
            run_id: run_id.to_string(),
            avg_mbps: avg,
            peak_mbps: peak,
            bytes_total: bytes,
            p50_rtt_ms: p50,
            p95_rtt_ms: p95,
            p99_rtt_ms: p99,
            retransmits,
            sack_active: sack,
            phases: LatencyPhases {
                connect_ms: self.connect_ms,
                handshake_ms: self.handshake_ms,
                first_byte_ms: self.first_byte_ms,
                ramp_ms: self.ramp_ms,
                steady_ms: self.steady_ms,
                teardown_ms: self.teardown_ms,
                ..Default::default()
            },
            frame: None,
            lanes: Vec::new(),
        }
    }

    /// Same as `finish`, but folds in the multi-file report and splits per-frame
    /// time into filesystem versus wire — the numbers behind the Gantt's I/O band.
    ///
    /// `recv_io_ms` stays zero here because only the receiver knows what writing the
    /// frames cost. The console fills it from the receiver's `Write` lane, which is
    /// streamed live; the old plan of merging two `RunComplete` summaries never
    /// worked, since the receiver is torn down rather than asked for one.
    #[allow(clippy::too_many_arguments)]
    pub fn finish_frames(&self, retransmits: u64, sack: bool, run_id: &str, peak: f64,
                         avg: f64, bytes: u64, rtts: &mut Vec<f64>,
                         counters: &frame::FrameCounters, elapsed_secs: f64,
                         lanes: &frame::Lanes) -> RunSummary {
        let mut s = self.finish(retransmits, sack, run_id, peak, avg, bytes, rtts);
        let stats = counters.finish(elapsed_secs);
        s.phases.send_io_ms = counters.mean_fs_ms();
        // Measured, not inferred. Subtracting disk time from whole-frame time —
        // which is what this used to do — quietly attributed every queue wait
        // and scheduler stall to the network.
        s.phases.net_ms = counters.wire.snapshot().avg_ms;
        s.frame = Some(stats);
        s.lanes = lanes.snapshot_all(run_id, "send");
        s
    }
}

fn percentiles(rtts: &mut Vec<f64>) -> (f64, f64, f64) {
    if rtts.is_empty() {
        return (0.0, 0.0, 0.0);
    }
    rtts.sort_by(|a, b| a.partial_cmp(b).unwrap());
    let at = |q: f64| {
        let idx = ((rtts.len() as f64 - 1.0) * q).round() as usize;
        rtts[idx]
    };
    (at(0.50), at(0.95), at(0.99))
}

/// Roughly-10 Hz sampler for a smooth live rolling window. Reads the shared
/// counters, computes instantaneous aggregate + per-stream throughput and sender
/// CPU%, streams a `TelemetrySample`, and records the RTT series for percentiles.
/// Stops on: external `stop`, `duration_secs` (unless `continuous`), or once the
/// aggregate reaches `bytes_target` (when set and not continuous).
/// Returns (peak_mbps, avg_mbps, rtt_series).
pub async fn sample_loop(
    run_id: String,
    end: &str,
    counters: Arc<Counters>,
    stop: Stop,
    tx: Tx,
    duration_secs: u32,
    bytes_target: u64,
    continuous: bool,
) -> (f64, f64, Vec<f64>) {
    sample_loop_frames(run_id, end, counters, stop, tx, duration_secs, bytes_target,
                       continuous, None, 0, None)
        .await
}

/// `sample_loop` with the multi-file additions: per-interval frame progress on
/// each telemetry sample, and a frame-count stop condition so a run bounded by
/// `-n` ends when the last frame lands rather than on a clock.
#[allow(clippy::too_many_arguments)]
pub async fn sample_loop_frames(
    run_id: String,
    end: &str,
    counters: Arc<Counters>,
    stop: Stop,
    tx: Tx,
    duration_secs: u32,
    bytes_target: u64,
    continuous: bool,
    frames: Option<Arc<frame::FrameCounters>>,
    frame_target: u64,
    lanes: Option<Arc<frame::Lanes>>,
) -> (f64, f64, Vec<f64>) {
    const HZ: u64 = 5; // samples per second
    let start = Instant::now();
    let mut last_bytes = 0u64;
    let mut last_pkts = 0u64;
    let mut last_streams = vec![0u64; counters.streams.len()];
    let mut last_t = 0.0f64;
    let mut peak = 0.0f64;
    let mut warmup_peak = 0.0f64;
    let mut rtts: Vec<f64> = Vec::new();
    crate::sysstat::start();
    let mut ticker = tokio::time::interval(std::time::Duration::from_millis(1000 / HZ));
    ticker.tick().await;

    loop {
        ticker.tick().await;
        let t = start.elapsed().as_secs_f64();
        let bytes = counters.bytes.load(Ordering::Relaxed);
        let pkts = counters.packets.load(Ordering::Relaxed);
        let dt = (t - last_t).max(1e-6);
        let mbps = ((bytes - last_bytes) as f64 * 8.0) / dt / 1.0e6;
        let pps = (pkts - last_pkts) as f64 / dt;
        let rtt_ms = counters.rtt_us.load(Ordering::Relaxed) as f64 / 1000.0;
        if rtt_ms > 0.0 {
            rtts.push(rtt_ms);
        }
        // Per-stream goodput this interval.
        let mut per_stream = Vec::with_capacity(counters.streams.len());
        for (i, s) in counters.streams.iter().enumerate() {
            let cur = s.load(Ordering::Relaxed);
            let d = cur.saturating_sub(last_streams[i]);
            per_stream.push((d as f64 * 8.0) / dt / 1.0e6);
            last_streams[i] = cur;
        }
        // The opening interval counts writes that merely filled socket buffers,
        // not sustained delivery, so it reads far above line rate. Keep the raw
        // sample (it is what was measured) but leave it out of the reported
        // peak — same reason iperf3 has --omit.
        const PEAK_OMIT_SECS: f64 = 1.0;
        if t >= PEAK_OMIT_SECS {
            peak = peak.max(mbps);
        } else {
            // A run shorter than the omit window would otherwise report a peak of
            // zero, which reads as "no throughput" rather than "not enough run to
            // separate steady state from the buffer fill". Frame runs bounded by a
            // small `-n` land here routinely. Track the warm-up separately and
            // fall back to it below rather than publishing a zero.
            warmup_peak = warmup_peak.max(mbps);
        }
        let frame_progress = frames.as_ref().map(|f| f.tick(t));
        let frames_done = frame_progress.as_ref().map(|p| p.frames_done).unwrap_or(0);
        let frames_dropped = frame_progress
            .as_ref()
            .map(|p| p.frames_dropped)
            .unwrap_or(0);
        let _ = tx.send(crate::protocol::AgentMsg::Telemetry(TelemetrySample {
            run_id: run_id.clone(),
            end: end.to_string(),
            t_secs: t,
            mbps,
            pps,
            rtt_ms,
            retransmits: counters.retransmits.load(Ordering::Relaxed),
            cpu_percent: crate::sysstat::current(),
            per_stream,
            frame: frame_progress,
        }));

        // Lifecycle lanes ride the same tick, so the Gantt fills in as the work
        // happens rather than appearing whole at the end of the run.
        if let Some(l) = lanes.as_ref() {
            for u in l.snapshot_all(&run_id, end) {
                let _ = tx.send(crate::protocol::AgentMsg::Phase(u));
            }
        }

        last_bytes = bytes;
        last_pkts = pkts;
        last_t = t;

        // A frame-bounded run ends on its frame budget, not a clock — so the
        // byte/time conditions are suppressed entirely when one is set.
        let framed = frame_target > 0;
        let hit_bytes = !framed && !continuous && bytes_target > 0 && bytes >= bytes_target;
        let hit_time =
            !framed && !continuous && bytes_target == 0 && t >= duration_secs as f64;
        // Done when every frame is accounted for — transferred or dropped.
        // Dropped frames count as retired, or a run that cannot keep up with its
        // fps deadline would never finish.
        let hit_frames = framed && frames_done + frames_dropped >= frame_target;
        if stop.load(Ordering::Relaxed) || hit_bytes || hit_time || hit_frames {
            if hit_bytes || hit_time || hit_frames {
                stop.store(true, Ordering::Relaxed);
            }
            break;
        }
    }
    let total = counters.bytes.load(Ordering::Relaxed);
    let elapsed = start.elapsed().as_secs_f64().max(1e-6);
    let avg = (total as f64 * 8.0) / elapsed / 1.0e6;
    // Sub-second run: no sample cleared the omit window, so report the warm-up
    // peak rather than zero. It overstates sustained rate, which is exactly why
    // it is only ever the fallback.
    if peak <= 0.0 {
        peak = warmup_peak;
    }
    (peak, avg, rtts)
}

/// Spawn the receiver-side sampler: same loop as the sender's, but tagged
/// `end: "recv"` and running until the receiver's stop flag is set (the console
/// tears the receiver down when the sender reports the run complete). What the
/// receiver measures is *delivered* goodput — the other half of the balance view.
pub fn spawn_recv_sampler(run_id: String, counters: Arc<Counters>, stop: Stop, tx: Tx) {
    spawn_recv_sampler_frames(run_id, counters, stop, tx, None, None)
}

/// Receiver sampler that also reports the receiver's own frame progress and I/O timings.
/// The receiver is the only place that knows what writing the received frames cost,
/// which is the other half of the Gantt's I/O band.
pub fn spawn_recv_sampler_frames(
    run_id: String,
    counters: Arc<Counters>,
    stop: Stop,
    tx: Tx,
    frames: Option<Arc<frame::FrameCounters>>,
    lanes: Option<Arc<frame::Lanes>>,
) {
    tokio::spawn(async move {
        // duration/bytes 0 + continuous: only the stop flag ends this loop.
        sample_loop_frames(run_id.clone(), "recv", counters, stop, tx.clone(), 0, 0, true,
                           frames, 0, lanes.clone())
            .await;
        // The receiver never sends RunComplete — the console tears it down when the
        // sender finishes — so this final burst is the only chance to publish
        // the receive/write lanes as closed rather than still running.
        if let Some(l) = lanes {
            l.finish_all();
            for u in l.snapshot_all(&run_id, "recv") {
                let _ = tx.send(crate::protocol::AgentMsg::Phase(u));
            }
        }
    });
}

/// Dispatch: run the sender side of a scenario against `target_addr`.
pub async fn run_send(
    run_id: String,
    scenario: Scenario,
    target_addr: String,
    tx: Tx,
    stop: Stop,
) -> Result<RunSummary> {
    guard_dpdk(&scenario)?;
    guard_multi_file(&scenario)?;
    match scenario.protocol {
        Protocol::Tcp | Protocol::TcpSack => {
            tcp::run_send(run_id, scenario, target_addr, tx, stop).await
        }
        Protocol::Udp => udp::run_send(run_id, scenario, target_addr, tx, stop).await,
        Protocol::UdpDpdk => dpdk::run_send(run_id, scenario, target_addr, tx, stop).await,
        // Both QUIC variants share one engine; the scenario's protocol selects
        // kernel UDP or the DPDK datapath underneath quinn.
        Protocol::Quic | Protocol::QuicDpdk => {
            quic::run_send(run_id, scenario, target_addr, tx, stop).await
        }
    }
}

/// Dispatch: bind the receiver side and return the address the sender should hit,
/// plus a stop flag the caller keeps to tear the receiver down later.
pub async fn run_recv(
    run_id: String,
    scenario: Scenario,
    tx: Tx,
) -> Result<(std::net::SocketAddr, Stop)> {
    guard_dpdk(&scenario)?;
    guard_multi_file(&scenario)?;
    match scenario.protocol {
        Protocol::Tcp | Protocol::TcpSack => tcp::run_recv(run_id, scenario, tx).await,
        Protocol::Udp => udp::run_recv(run_id, scenario, tx).await,
        Protocol::UdpDpdk => dpdk::run_recv(run_id, scenario, tx).await,
        Protocol::Quic | Protocol::QuicDpdk => quic::run_recv(run_id, scenario, tx).await,
    }
}

/// Refuse DPDK-backed protocols up front on hosts/builds that can't drive a
/// poll-mode driver, so the console gets one clear reason instead of a failure
/// buried in the datapath.
fn guard_dpdk(sc: &Scenario) -> Result<()> {
    if sc.protocol.needs_dpdk() && !dpdk::available() {
        anyhow::bail!("{:?} requires DPDK: {}", sc.protocol, dpdk::unavailable_reason());
    }
    Ok(())
}

/// Multi-file mode needs a reliable, ordered transport: a frame either arrives
/// whole or it did not arrive, and "frames transferred" is only a meaningful
/// number if that holds. The UDP paths give neither guarantee — a 50 MB 4K frame
/// does not fit in a datagram, and reassembling one over lossy delivery would
/// produce torn frames we would then have to count as *something*.
///
/// Rather than invent fragmentation semantics the original never had, refuse the
/// combination and say why. TCP, TCP+SACK and both QUIC variants all support it.
fn guard_multi_file(sc: &Scenario) -> Result<()> {
    if sc.is_multi_file() && matches!(sc.protocol, Protocol::Udp | Protocol::UdpDpdk) {
        anyhow::bail!(
            "multi-file mode needs a reliable transport; {:?} cannot guarantee whole \
             frames. Use TCP, TCP+SACK or QUIC.",
            sc.protocol
        );
    }
    Ok(())
}

/// Helper for the Selector vs Threaded decision at call sites.
pub fn is_selector(sc: &Scenario) -> bool {
    matches!(sc.architecture, Architecture::Selector)
}

/// The port range receivers bind for the data plane, as `(first, last)`. `(0, 0)` —
/// the default — means "any free port", which is right whenever peers can reach
/// each other directly.
///
/// Ephemeral is exactly wrong behind a container port mapping: a published port
/// is fixed when the container starts, so a per-run port can never be forwarded.
/// Pinning a range makes a containerised receiver reachable from the host while
/// still allowing more than one run at a time — a single port would let the
/// second concurrent receiver fail to bind.
static RECV_PORTS: (std::sync::atomic::AtomicU16, std::sync::atomic::AtomicU16) =
    (std::sync::atomic::AtomicU16::new(0), std::sync::atomic::AtomicU16::new(0));

/// Set once at startup from `--data-port`.
pub fn set_recv_ports(first: u16, last: u16) {
    RECV_PORTS.0.store(first, std::sync::atomic::Ordering::Relaxed);
    RECV_PORTS.1.store(last.max(first), std::sync::atomic::Ordering::Relaxed);
}

/// Bind a receiver socket, trying each configured port in turn.
///
/// `bind` is handed an address and returns the bound socket. Only an in-use port
/// moves us to the next candidate — any other failure is a real error and is
/// reported as-is rather than retried against ports that will fail identically.
pub fn bind_recv<T>(
    mut bind: impl FnMut(std::net::SocketAddr) -> Result<T>,
) -> Result<T> {
    let first = RECV_PORTS.0.load(std::sync::atomic::Ordering::Relaxed);
    let last = RECV_PORTS.1.load(std::sync::atomic::Ordering::Relaxed);
    // Port 0 lets the kernel choose, so there is nothing to walk.
    if first == 0 {
        return bind(std::net::SocketAddr::from(([0, 0, 0, 0], 0)));
    }

    let mut last_err = None;
    for port in first..=last {
        match bind(std::net::SocketAddr::from(([0, 0, 0, 0], port))) {
            Ok(v) => return Ok(v),
            Err(e) if is_addr_in_use(&e) => last_err = Some(e),
            Err(e) => return Err(e),
        }
    }
    Err(last_err.unwrap_or_else(|| {
        anyhow::anyhow!("no free data port in {first}-{last}")
    }))
    .with_context(|| format!("bind receiver in data-port range {first}-{last}"))
}

fn is_addr_in_use(e: &anyhow::Error) -> bool {
    e.downcast_ref::<std::io::Error>()
        .map(|io| io.kind() == std::io::ErrorKind::AddrInUse)
        .unwrap_or(false)
}

/// How long to wait on a single candidate before trying the next one. Every
/// candidate is on the local fabric, so a reachable one answers in well under
/// this; the budget only bounds how long a black-holed address can stall a run.
const PROBE_TIMEOUT: std::time::Duration = std::time::Duration::from_millis(400);

/// Expand a target into the full candidate list to try, best first.
///
/// `target` arrives as a comma-separated list, best guess first: the receiver's own
/// advertised address, plus whatever the console observed. Both can be wrong in
/// a mixed host/container run, and one way is worth correcting here.
///
/// A loopback candidate means "the receiver is on the same box as whoever wrote
/// this". From a sender in a container that is false by construction — the receiver
/// is a different process in a different namespace, so the container's own
/// loopback is the one address guaranteed *not* to have it. The host gateway is
/// what was meant, so we add it. We append rather than replace: a sender and
/// receiver sharing a container really can talk over loopback.
fn candidates(target: &str) -> Vec<String> {
    // A free fn rather than a closure: the closure would hold a mutable borrow
    // of `out` for its whole scope, and the rewrite below has to read `out`.
    fn push(out: &mut Vec<String>, c: String) {
        if !c.is_empty() && !out.contains(&c) {
            out.push(c);
        }
    }

    let mut out: Vec<String> = Vec::new();
    for candidate in target.split(',').map(str::trim).filter(|c| !c.is_empty()) {
        push(&mut out, candidate.to_string());
    }

    if crate::netenv::in_container() {
        if let Some(gw) = crate::netenv::host_gateway() {
            let rewritten: Vec<String> = out
                .iter()
                .filter_map(|c| split_host_port(c))
                .filter(|(host, _)| crate::netenv::is_loopback_host(host))
                .map(|(_, port)| format!("{gw}:{port}"))
                .collect();
            for c in rewritten {
                push(&mut out, c);
            }
        }
    }
    out
}

/// Split `host:port`, tolerating bracketed IPv6. `None` when there is no port.
fn split_host_port(s: &str) -> Option<(String, String)> {
    if let Some(rest) = s.strip_prefix('[') {
        let (host, tail) = rest.split_once(']')?;
        let port = tail.strip_prefix(':')?;
        return Some((host.to_string(), port.to_string()));
    }
    let (host, port) = s.rsplit_once(':')?;
    if host.is_empty() || port.is_empty() || host.contains(':') {
        return None; // bare IPv6, no port
    }
    Some((host.to_string(), port.to_string()))
}

/// Resolve a target to a concrete socket address, without testing reachability.
///
/// Used by the datagram paths (UDP, QUIC, DPDK), where there is no listener to
/// shake hands with and so nothing to probe: candidate *order* is the only
/// signal available, which is why `candidates` puts the corrected forms in.
pub fn resolve(target: &str) -> Result<std::net::SocketAddr> {
    use std::net::ToSocketAddrs;
    let mut errors = Vec::new();
    for candidate in candidates(target) {
        match candidate.to_socket_addrs() {
            Ok(mut addrs) => match addrs.next() {
                Some(addr) => return Ok(addr),
                None => errors.push(format!("{candidate}: no addresses")),
            },
            Err(e) => errors.push(format!("{candidate}: {e}")),
        }
    }
    anyhow::bail!("resolve target addr, tried {}", errors.join("; "))
}

/// Resolve a target to an address something is actually *listening* on.
///
/// Resolving and reaching are different questions, and for a candidate list they
/// are the question. Every candidate here is an IP literal or a name that
/// resolves locally, so a resolve-only walk always stops at the first entry and
/// the rest of the list is decoration — which is exactly how a host receiver's
/// `127.0.0.1` gets handed to a containerised sender and refused. So we connect.
///
/// Only for the stream protocols, where a completed handshake proves the receiver is
/// there. With one candidate we skip the probe: there is no choice to make, and
/// the real connect below will report a far better error than we could.
pub fn resolve_connectable(target: &str) -> Result<std::net::SocketAddr> {
    use std::net::ToSocketAddrs;
    let candidates = candidates(target);
    if candidates.len() < 2 {
        return resolve(target);
    }

    let mut errors = Vec::new();
    let mut first_resolved = None;
    for candidate in &candidates {
        let addrs = match candidate.to_socket_addrs() {
            Ok(a) => a.collect::<Vec<_>>(),
            Err(e) => {
                errors.push(format!("{candidate}: {e}"));
                continue;
            }
        };
        for addr in addrs {
            first_resolved.get_or_insert(addr);
            match std::net::TcpStream::connect_timeout(&addr, PROBE_TIMEOUT) {
                Ok(_) => {
                    tracing::debug!(%candidate, %addr, "target reachable");
                    return Ok(addr);
                }
                Err(e) => errors.push(format!("{candidate} ({addr}): {e}")),
            }
        }
    }

    // Nothing answered. A receiver that is still binding is the likeliest reason, so
    // hand back the best-guess address and let the real connect retry and report.
    match first_resolved {
        Some(addr) => {
            tracing::warn!(%addr, "no candidate answered a probe; trying anyway");
            Ok(addr)
        }
        None => anyhow::bail!("resolve target addr, tried {}", errors.join("; ")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn host_and_port_split_on_the_last_colon() {
        assert_eq!(split_host_port("127.0.0.1:5000"),
                   Some(("127.0.0.1".into(), "5000".into())));
        assert_eq!(split_host_port("edge1:9"), Some(("edge1".into(), "9".into())));
        assert_eq!(split_host_port("[::1]:5000"), Some(("::1".into(), "5000".into())));
    }

    #[test]
    fn addresses_without_a_port_are_not_split() {
        assert_eq!(split_host_port("127.0.0.1"), None);
        assert_eq!(split_host_port("fe80::1"), None);
        assert_eq!(split_host_port("edge1:"), None);
    }

    #[test]
    fn candidate_list_is_parsed_in_order_and_deduped() {
        let c = candidates("edge1:5000, 172.18.0.3:5000 ,edge1:5000");
        assert_eq!(c, vec!["edge1:5000", "172.18.0.3:5000"]);
    }

    #[test]
    fn a_single_target_is_left_alone() {
        assert_eq!(candidates("10.99.0.2:5000"), vec!["10.99.0.2:5000"]);
    }

    /// Off-host, loopback is passed through untouched — a sender and receiver really
    /// can share a box, and rewriting there would break the single-host case.
    #[test]
    fn loopback_survives_outside_a_container() {
        if crate::netenv::in_container() {
            return; // the rewrite is correct here; asserted by the container case
        }
        assert_eq!(candidates("127.0.0.1:5000"), vec!["127.0.0.1:5000"]);
    }

    /// The bug this all exists for: a probe must pick the candidate that answers,
    /// not the first that merely parses. An unbound port on loopback refuses fast,
    /// so a listener later in the list is the only reachable one.
    #[test]
    fn probe_skips_a_refusing_candidate_for_a_listening_one() {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let live = listener.local_addr().unwrap();

        // Bind and drop to get a port that is almost certainly free — and so
        // refuses immediately rather than hanging.
        let dead = {
            let s = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
            s.local_addr().unwrap()
        };

        let target = format!("{dead},{live}");
        assert_eq!(resolve_connectable(&target).unwrap(), live);
    }

    /// With nothing listening we still hand back an address: the receiver may simply
    /// not have finished binding, and the real connect reports far better.
    #[test]
    fn probe_falls_back_to_the_first_address_when_none_answer() {
        let dead = {
            let s = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
            s.local_addr().unwrap()
        };
        let target = format!("{dead},127.0.0.1:1");
        assert_eq!(resolve_connectable(&target).unwrap(), dead);
    }
}

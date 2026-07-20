//! The data plane. A source agent generates load against a sink agent; both are
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
    /// TCP retransmits (Linux TCP_INFO) or UDP datagrams the sink reported missing.
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

/// Common TCP socket tuning shared by source and sink.
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
        }
    }

    /// Same as `finish`, but folds in the multi-file report and splits per-frame
    /// time into filesystem versus wire — the numbers behind the Gantt's I/O band.
    ///
    /// `sink_io_ms` is left at zero here: only the sink knows its own I/O cost,
    /// and the console merges the two summaries.
    #[allow(clippy::too_many_arguments)]
    pub fn finish_frames(&self, retransmits: u64, sack: bool, run_id: &str, peak: f64,
                         avg: f64, bytes: u64, rtts: &mut Vec<f64>,
                         counters: &frame::FrameCounters, elapsed_secs: f64) -> RunSummary {
        let mut s = self.finish(retransmits, sack, run_id, peak, avg, bytes, rtts);
        let stats = counters.finish(elapsed_secs);
        let src_io = counters.mean_fs_ms();
        // Whatever a frame cost beyond its filesystem work went to the wire.
        s.phases.src_io_ms = src_io;
        s.phases.net_ms = (stats.file.avg_ms - src_io).max(0.0);
        s.frame = Some(stats);
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
/// counters, computes instantaneous aggregate + per-stream throughput and source
/// CPU%, streams a `TelemetrySample`, and records the RTT series for percentiles.
/// Stops on: external `stop`, `duration_secs` (unless `continuous`), or once the
/// aggregate reaches `bytes_target` (when set and not continuous).
/// Returns (peak_mbps, avg_mbps, rtt_series).
pub async fn sample_loop(
    run_id: String,
    role: &str,
    counters: Arc<Counters>,
    stop: Stop,
    tx: Tx,
    duration_secs: u32,
    bytes_target: u64,
    continuous: bool,
) -> (f64, f64, Vec<f64>) {
    sample_loop_frames(run_id, role, counters, stop, tx, duration_secs, bytes_target,
                       continuous, None, 0)
        .await
}

/// `sample_loop` with the multi-file additions: per-interval frame progress on
/// each telemetry sample, and a frame-count stop condition so a run bounded by
/// `-n` ends when the last frame lands rather than on a clock.
#[allow(clippy::too_many_arguments)]
pub async fn sample_loop_frames(
    run_id: String,
    role: &str,
    counters: Arc<Counters>,
    stop: Stop,
    tx: Tx,
    duration_secs: u32,
    bytes_target: u64,
    continuous: bool,
    frames: Option<Arc<frame::FrameCounters>>,
    frame_target: u64,
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
            role: role.to_string(),
            t_secs: t,
            mbps,
            pps,
            rtt_ms,
            retransmits: counters.retransmits.load(Ordering::Relaxed),
            cpu_percent: crate::sysstat::current(),
            per_stream,
            frame: frame_progress,
        }));
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

/// Spawn the sink-side sampler: same loop as the source's, but tagged
/// `role: "sink"` and running until the sink's stop flag is set (the console
/// tears the sink down when the source reports the run complete). What the
/// sink measures is *delivered* goodput — the other half of the balance view.
pub fn spawn_sink_sampler(run_id: String, counters: Arc<Counters>, stop: Stop, tx: Tx) {
    spawn_sink_sampler_frames(run_id, counters, stop, tx, None)
}

/// Sink sampler that also reports the sink's own frame progress and I/O timings.
/// The sink is the only place that knows what writing the received frames cost,
/// which is the other half of the Gantt's I/O band.
pub fn spawn_sink_sampler_frames(
    run_id: String,
    counters: Arc<Counters>,
    stop: Stop,
    tx: Tx,
    frames: Option<Arc<frame::FrameCounters>>,
) {
    tokio::spawn(async move {
        // duration/bytes 0 + continuous: only the stop flag ends this loop.
        sample_loop_frames(run_id, "sink", counters, stop, tx, 0, 0, true, frames, 0).await;
    });
}

/// Dispatch: run the source side of a scenario against `target_addr`.
pub async fn run_source(
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
            tcp::run_source(run_id, scenario, target_addr, tx, stop).await
        }
        Protocol::Udp => udp::run_source(run_id, scenario, target_addr, tx, stop).await,
        Protocol::UdpDpdk => dpdk::run_source(run_id, scenario, target_addr, tx, stop).await,
        // Both QUIC variants share one engine; the scenario's protocol selects
        // kernel UDP or the DPDK datapath underneath quinn.
        Protocol::Quic | Protocol::QuicDpdk => {
            quic::run_source(run_id, scenario, target_addr, tx, stop).await
        }
    }
}

/// Dispatch: bind the sink side and return the address the source should hit,
/// plus a stop flag the caller keeps to tear the sink down later.
pub async fn run_sink(
    run_id: String,
    scenario: Scenario,
    tx: Tx,
) -> Result<(std::net::SocketAddr, Stop)> {
    guard_dpdk(&scenario)?;
    guard_multi_file(&scenario)?;
    match scenario.protocol {
        Protocol::Tcp | Protocol::TcpSack => tcp::run_sink(run_id, scenario, tx).await,
        Protocol::Udp => udp::run_sink(run_id, scenario, tx).await,
        Protocol::UdpDpdk => dpdk::run_sink(run_id, scenario, tx).await,
        Protocol::Quic | Protocol::QuicDpdk => quic::run_sink(run_id, scenario, tx).await,
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

/// Resolve a `host:port` target to a concrete socket address. The advertised
/// address may be a hostname (e.g. a container/service name on a shared
/// network), so we go through DNS rather than a literal-IP parse.
pub fn resolve(target: &str) -> Result<std::net::SocketAddr> {
    use std::net::ToSocketAddrs;
    target
        .to_socket_addrs()
        .with_context(|| format!("resolve target addr {target}"))?
        .next()
        .with_context(|| format!("no addresses for {target}"))
}

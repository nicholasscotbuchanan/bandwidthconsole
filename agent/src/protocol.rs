//! Wire protocol between `bwagent` and the JavaFX control plane.
//!
//! Transport is newline-delimited JSON (NDJSON) over a plain TCP socket.
//! The agent dials *out* to the console (so agents behind NAT still reach the
//! nexus), then this bidirectional stream carries commands down and telemetry up.
//!
//! Every message is a JSON object with a `"type"` discriminator; serde's
//! internally-tagged enums produce/consume exactly that shape, and the Java
//! side mirrors these structs field-for-field.

use serde::{Deserialize, Serialize};

/// Supported wire protocols for the data plane. `UdpDpdk` is only selectable
/// when the receiver+sender agents both report the `dpdk` capability.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum Protocol {
    Tcp,
    /// TCP with selective acknowledgements. SACK is a kernel/system attribute
    /// (Linux `net.ipv4.tcp_sack`), so this flag is honoured best-effort and the
    /// agent reports whether SACK is actually active.
    TcpSack,
    Udp,
    /// UDP over a DPDK poll-mode driver (Linux + hugepages + the `dpdk` feature).
    UdpDpdk,
    /// QUIC (always TLS 1.3 over UDP), via quinn. Streams multiplex on one
    /// connection or spread across many (see `single_connection`).
    Quic,
    /// QUIC carried over a DPDK kernel-bypass datapath instead of kernel UDP
    /// sockets: the same quinn stack, but its `AsyncUdpSocket` is backed by a
    /// poll-mode driver. Linux + `dpdk` feature only.
    QuicDpdk,
}

impl Protocol {
    /// Whether this protocol needs the DPDK datapath (and so the `dpdk` capability).
    pub fn needs_dpdk(&self) -> bool {
        matches!(self, Protocol::UdpDpdk | Protocol::QuicDpdk)
    }
}

/// How the sender generates load — the core architectural question the tool
/// exists to answer.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum Architecture {
    /// One OS thread per connection, blocking I/O.
    Threaded,
    /// A single async runtime multiplexing all connections (epoll/kqueue).
    Selector,
}

/// What *shape* of data crosses the wire. The question this answers: how much
/// bandwidth do you lose moving thousands of discrete frame files instead of one
/// continuous stream?
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum TransferMode {
    /// One continuous byte stream per connection — the original behaviour, and
    /// what DVS frametest calls streaming mode (`-s`).
    LargeFile,
    /// Discrete frames, each paying open/IO/close on both ends, paced against an
    /// fps deadline with frametest's queue-depth drop accounting.
    MultiFile,
}

impl Default for TransferMode {
    fn default() -> Self {
        TransferMode::LargeFile
    }
}

/// frametest's test mode: `-w` (write), `-r` (read), `-e` (empty/open-close only).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum FrameMode {
    /// `-w size` — create and transmit frames.
    Write,
    /// `-r` — read pre-existing frames from the sender path. frametest's default.
    Read,
    /// `-e` — zero-length frames: isolates open/close cost (a metadata/IOPS test).
    Empty,
}

impl Default for FrameMode {
    fn default() -> Self {
        FrameMode::Write
    }
}

/// The order frames are created/accessed in: default sequential, `-v` reverse,
/// `-m` random.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum FrameOrder {
    Sequential,
    Reverse,
    Random,
}

impl Default for FrameOrder {
    fn default() -> Self {
        FrameOrder::Sequential
    }
}

/// Whether frames actually touch a filesystem. `Disk` is faithful to frametest
/// and measures storage+network together; `Memory` synthesises frames in RAM and
/// discards them at the receiver, isolating the pure network cost of many small
/// transfers. Running the same scenario both ways tells you which one is the
/// bottleneck.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum FrameStorage {
    Disk,
    Memory,
}

impl Default for FrameStorage {
    fn default() -> Self {
        FrameStorage::Disk
    }
}

/// Named frame-size presets, matching frametest's `-w sd|hd|2k|4k`.
///
/// The `2k` and `4k` byte counts are **verified** byte-exact: the DVS reference
/// output echoes `-w12512` for `-w 2k`, and the tuxera/tframetest clone writes
/// exactly 12,812,288 / 51,052,544 byte files. `sd` and `hd` are **inferred**
/// from the clone's 720x480 and 1920x1080 32-bit profiles — no source documents
/// the original's values, and real SD DPX is 720x486 rather than 720x480, so
/// treat those two as approximate.
///
/// Geometry is full-aperture DPX at 4 bytes/pixel (which is also how 10-bit DPX
/// packs: 3x10 bits into one 32-bit word). A header — 64 KB by default, see
/// `FrameSpec::header_kb` — is added on top of the payload.
///
/// Payloads are rounded up to a 4096-byte boundary. Only `sd` is affected
/// (720x480x4 = 1,382,400 -> 1,384,448); the other three geometries land on the
/// boundary exactly, which is itself the strongest evidence that the original
/// uses direct I/O.
pub fn preset_payload_bytes(name: &str) -> Option<u64> {
    let raw: u64 = match name.to_ascii_lowercase().as_str() {
        "sd" => 720 * 480 * 4,        // 1,382,400 -> 1,384,448 (inferred)
        "hd" => 1920 * 1080 * 4,      // 8,294,400              (inferred)
        "2k" => 2048 * 1556 * 4,      // 12,746,752             (verified)
        "4k" => 4096 * 3112 * 4,      // 50,987,008             (verified)
        _ => return None,
    };
    Some(align_up(raw, 4096))
}

/// Direct I/O wants 4096-aligned lengths; every preset above already is one, but
/// custom `-z` sizes may not be.
pub fn align_up(n: u64, to: u64) -> u64 {
    if to == 0 {
        return n;
    }
    n.div_ceil(to) * to
}

fn default_frame_count() -> u64 {
    1800 // frametest's -n default
}
fn default_prebuffer() -> u32 {
    5 // frametest's -b default
}
fn default_header_kb() -> u32 {
    64 // frametest's --header default
}
fn default_ios_per_frame() -> u32 {
    1 // frametest's -i default
}

/// The frametest-compatible half of a scenario. Only meaningful when
/// `Scenario::transfer_mode` is `MultiFile`; ignored otherwise.
///
/// Field-by-field this is DVS frametest's option set. Two flags are commonly
/// misremembered and are called out here because getting them wrong is worse
/// than not having them: **`-o` is out-of-order I/O completion, not an output
/// file** (CSV export is `-x`), and **`-v` is reverse order, not verbose**.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FrameSpec {
    /// `-w` / `-r` / `-e`.
    #[serde(default)]
    pub mode: FrameMode,
    /// Total bytes per frame *including* the header. Resolved from a preset or
    /// from `-z`, then aligned up for direct I/O.
    pub frame_bytes: u64,
    /// `-n` — how many frames to move.
    #[serde(default = "default_frame_count")]
    pub frame_count: u64,
    /// `-f` — target frames per second; 0 = unpaced (go as fast as possible).
    #[serde(default)]
    pub fps_limit: f64,
    /// `-q` — how many frames may be queued before one is counted dropped.
    /// 0 = unbounded, i.e. no drop accounting.
    #[serde(default)]
    pub queue_depth: u32,
    /// `-b` — frames staged before the clock starts.
    #[serde(default = "default_prebuffer")]
    pub prebuffer: u32,
    /// default / `-v` / `-m`.
    #[serde(default)]
    pub order: FrameOrder,
    /// Disk or Memory.
    #[serde(default)]
    pub storage: FrameStorage,
    /// Directory frames are read from / written to. Required for `Disk`.
    #[serde(default)]
    pub path: String,
    /// Where the receiver writes received frames. Empty = same as `path`.
    #[serde(default)]
    pub dest_path: String,
    /// `--header` — per-file header size in KB.
    #[serde(default = "default_header_kb")]
    pub header_kb: u32,
    /// `-d` — spread files across subdirectories, this many per directory.
    /// 0 = one flat directory.
    #[serde(default)]
    pub files_per_dir: u32,
    /// `--name` — filename pattern of the first file. Empty = `frame%06u.tst`.
    #[serde(default)]
    pub name_pattern: String,
    /// `-a` — overlapped/async I/O depth. 0 = synchronous.
    #[serde(default)]
    pub async_depth: u32,
    /// `-o` — allow I/Os to complete out of order (async mode only).
    #[serde(default)]
    pub out_of_order: bool,
    /// `-l` — extra loops over the frame set. 0 = don't loop.
    #[serde(default)]
    pub loop_frames: u64,
    /// `-i` — split each frame into this many I/O calls.
    #[serde(default = "default_ios_per_frame")]
    pub ios_per_frame: u32,
    /// `-c` — read only the middle N% of each frame. 0/100 = whole frame.
    #[serde(default)]
    pub crop_percent: u32,
    /// `-p` — sleep this long before starting (lets caches settle).
    #[serde(default)]
    pub pause_secs: u32,
    /// `--prealloc` — preallocate this many frames of space in streaming mode.
    #[serde(default)]
    pub prealloc: u64,
    /// Bypass the page cache (O_DIRECT / F_NOCACHE / FILE_FLAG_NO_BUFFERING).
    ///
    /// Defaults on. The original's buffering behaviour is **undocumented**; we
    /// infer direct I/O from its frame sizes all being exact 4096 multiples and
    /// from the tframetest clone using it unconditionally. Turn off to compare
    /// against a buffered baseline.
    #[serde(default = "default_true")]
    pub direct_io: bool,
}

fn default_true() -> bool {
    true
}

impl FrameSpec {
    /// Payload bytes per frame, i.e. the file minus its header.
    pub fn payload_bytes(&self) -> u64 {
        self.frame_bytes
            .saturating_sub(self.header_kb as u64 * 1024)
    }

    /// Bytes actually read per frame once `-c` cropping is applied.
    pub fn cropped_bytes(&self) -> u64 {
        let p = self.payload_bytes();
        if self.crop_percent == 0 || self.crop_percent >= 100 {
            p
        } else {
            (p * self.crop_percent as u64) / 100
        }
    }

    /// Total frames including `-l` loops.
    pub fn total_frames(&self) -> u64 {
        self.frame_count.saturating_mul(self.loop_frames + 1)
    }
}

/// The full description of a test run — this is what every slider in the UI
/// ultimately sets.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Scenario {
    pub protocol: Protocol,
    pub architecture: Architecture,
    /// Concurrent worker threads (or async tasks in Selector mode).
    pub threads: u32,
    /// Concurrent OS processes; the sender forks this many workers and
    /// aggregates their throughput. 1 = single-process.
    pub processes: u32,
    /// Differentiated Services Code Point (0..=63). `dscp_enabled` gates whether
    /// it is written to the socket (IP_TOS = dscp << 2).
    pub dscp: u8,
    pub dscp_enabled: bool,
    /// Application payload size per send, in bytes.
    pub payload_bytes: u32,
    /// For rate-limited UDP: target offered load in Mbit/s (0 = unthrottled).
    pub target_mbps: u32,
    pub duration_secs: u32,
    /// Encrypt the TCP/SACK path with TLS 1.3 (rustls). QUIC is always encrypted
    /// regardless of this flag; UDP/DPDK ignore it.
    #[serde(default)]
    pub tls: bool,
    /// Total bytes to transfer before the run stops (0 = run for `duration_secs`).
    /// Lets the UI drive runs by payload size (e.g. 100 MB) like the reference.
    #[serde(default)]
    pub bytes_target: u64,
    /// Run until explicitly stopped, ignoring duration and byte target.
    #[serde(default)]
    pub continuous: bool,
    /// QUIC only: put all streams on a single connection (true) or one
    /// connection per stream (false).
    #[serde(default)]
    pub single_connection: bool,
    /// One continuous stream (default, and today's behaviour) or discrete frames.
    #[serde(default)]
    pub transfer_mode: TransferMode,
    /// frametest parameters. Only read when `transfer_mode` is `MultiFile`;
    /// `None` there is a configuration error the engine rejects up front.
    #[serde(default)]
    pub frame: Option<FrameSpec>,
}

impl Scenario {
    /// Whether this run moves discrete frames rather than a continuous stream.
    pub fn is_multi_file(&self) -> bool {
        matches!(self.transfer_mode, TransferMode::MultiFile)
    }
}

/// What a given agent can actually do, evaluated at startup on its host.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Capabilities {
    pub dpdk: bool,
    pub dscp: bool,
    /// Whether the kernel currently has TCP SACK enabled.
    pub sack: bool,
    pub max_threads: u32,
    pub cpu_count: u32,
}

/// Default for samples from agents that predate the `end` field: only senders
/// streamed telemetry back then, so "send" is the honest assumption.
fn default_end() -> String {
    "send".to_string()
}

/// One sampled point of a live run, streamed roughly once per second.
/// Both ends of a run emit these: the sender reports offered throughput, the
/// receiver reports delivered goodput — `end` says which side measured it.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TelemetrySample {
    pub run_id: String,
    /// "send" or "recv" — which end of the run measured this sample.
    #[serde(default = "default_end")]
    pub end: String,
    pub t_secs: f64,
    pub mbps: f64,
    pub pps: f64,
    /// Instantaneous application-observed RTT estimate, milliseconds.
    pub rtt_ms: f64,
    pub retransmits: u64,
    /// Sender process CPU utilisation over this interval, percent (0..100*cores).
    #[serde(default)]
    pub cpu_percent: f64,
    /// Per-stream goodput for this interval, Mbit/s, one entry per active stream.
    #[serde(default)]
    pub per_stream: Vec<f64>,
    /// Frame-mode progress for this interval. `None` for large-file runs, so the
    /// console can tell "no frames" apart from "zero frames".
    #[serde(default)]
    pub frame: Option<FrameProgress>,
}

/// The frame-mode half of a telemetry sample: where the run is against its frame
/// budget, and where the per-frame time is going.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FrameProgress {
    /// Frames completed this interval, per second.
    pub fps: f64,
    /// Cumulative frames successfully transferred.
    pub frames_done: u64,
    /// Cumulative frames dropped for missing their deadline with a full queue.
    pub frames_dropped: u64,
    /// Mean whole-frame time this interval, milliseconds.
    pub frame_ms_avg: f64,
    /// Mean open/create time this interval.
    pub open_ms_avg: f64,
    /// Mean data-transfer time this interval (read or write).
    pub io_ms_avg: f64,
    /// Mean close time this interval.
    pub close_ms_avg: f64,
}

/// One stage of a frame's lifecycle, as a Gantt lane.
///
/// A frame is generated on the sender's disk, read back, put on the wire,
/// received at the receiver, and written out there. Those five stages overlap
/// heavily — while frame 5 is in flight, 6 is being read and 4 is being written
/// — so each is reported as its own lane with a real wall-clock extent rather
/// than as a slice of a serial pipeline.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum Lane {
    /// Sender writes the frame set to disk, before any transmission starts.
    Generate,
    /// Sender reads a frame back off disk.
    Read,
    /// Sender hands a frame to the transport.
    Transmit,
    /// Receiver pulls a frame off the transport.
    Receive,
    /// Receiver writes the frame to its own disk.
    Write,
}

/// Live progress of one lifecycle lane, streamed while the run is happening so
/// the Gantt builds up as work occurs instead of appearing at the end.
///
/// `start_ms` / `end_ms` are offsets from the reporting agent's run epoch. The
/// sender and receiver have unsynchronised clocks, so the console anchors each
/// end's timeline on arrival rather than trusting the two to share an origin —
/// spacing within a end is exact, alignment across roles is approximate.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LaneUpdate {
    pub run_id: String,
    /// "send" or "recv" — whose clock `start_ms`/`end_ms` are on.
    pub end: String,
    pub lane: Lane,
    /// Offset of the lane's first work, ms from that agent's run epoch.
    pub start_ms: f64,
    /// Offset of its most recent work. Equals "now" while the lane is active.
    pub end_ms: f64,
    /// Summed time workers actually spent inside this lane. Less than
    /// `end_ms - start_ms` whenever the lane spends time waiting, which is how
    /// you tell "slow" apart from "idle waiting on someone else".
    pub busy_ms: f64,
    /// Frames that have passed through this lane so far.
    pub done: u64,
    /// Frames expected, for a progress fraction. 0 when unbounded.
    pub total: u64,
    /// Set once the lane will do no more work.
    pub complete: bool,
}

/// The per-phase latency breakdown that feeds the Gantt chart. Values are
/// milliseconds measured on the sender, offsets are from run start.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LatencyPhases {
    pub connect_ms: f64,
    pub handshake_ms: f64,
    pub first_byte_ms: f64,
    pub ramp_ms: f64,
    pub steady_ms: f64,
    pub teardown_ms: f64,
    /// Mean per-frame time the *sender* spent in filesystem I/O (open+read+close).
    /// Zero for large-file runs and near-zero in Memory storage mode — which is
    /// the point: it separates disk cost from wire cost on the Gantt.
    #[serde(default)]
    pub send_io_ms: f64,
    /// Mean per-frame time the *receiver* spent in filesystem I/O (open+write+close),
    /// reported by the receiver's own summary and merged by the console.
    #[serde(default)]
    pub recv_io_ms: f64,
    /// Mean per-frame time on the wire — whole-frame time minus both ends' I/O.
    #[serde(default)]
    pub net_ms: f64,
}

/// frametest's fixed frame-completion-time histogram buckets, in milliseconds.
/// Upper edge of each bucket; the 13th catches everything over 500 ms. These are
/// the original's exact edges so our histogram is directly comparable to output
/// from a real frametest run.
pub const HISTOGRAM_EDGES_MS: [f64; 12] =
    [0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0];

/// Column labels for `HISTOGRAM_EDGES_MS`, matching the original's axis.
pub const HISTOGRAM_LABELS: [&str; 13] = [
    "<0.1", ".2", ".5", "1", "2", "5", "10", "20", "50", "100", "200", "500", ">1s",
];

/// Place a duration into its histogram bucket index (0..=12).
pub fn histogram_bucket(ms: f64) -> usize {
    for (i, edge) in HISTOGRAM_EDGES_MS.iter().enumerate() {
        if ms < *edge {
            return i;
        }
    }
    HISTOGRAM_EDGES_MS.len() // the ">1s" catch-all
}

/// min/avg/max for one timed stage, milliseconds.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MinAvgMax {
    pub min_ms: f64,
    pub avg_ms: f64,
    pub max_ms: f64,
}

/// One row of frametest's "Averaged details" table: a trailing window plus the
/// stage means and rates observed within it.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WindowRow {
    /// "1s", "5s", "30s", or "Overall".
    pub label: String,
    pub open_ms: f64,
    pub io_ms: f64,
    pub frame_ms: f64,
    pub mb_per_sec: f64,
    pub fps: f64,
}

/// Everything frametest reports at the end of a run. `None` on large-file runs.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FrameStats {
    pub frames_transferred: u64,
    /// Frames that missed their deadline with a full queue. The number that says
    /// "this pipeline cannot sustain playback" — it is why the reference output
    /// shows 1796 of a requested 1800.
    pub frames_dropped: u64,
    pub bytes_total: u64,
    pub fastest_ms: f64,
    pub slowest_ms: f64,
    /// Whole-frame time (open + I/O + close).
    pub file: MinAvgMax,
    /// Open/create only.
    pub create: MinAvgMax,
    /// Data transfer only.
    pub io: MinAvgMax,
    pub close: MinAvgMax,
    /// Counts per `HISTOGRAM_EDGES_MS` bucket, 13 entries.
    pub histogram: Vec<u64>,
    /// "Averaged details" rows: last 1s / 5s / 30s / Overall.
    pub windows: Vec<WindowRow>,
}

impl FrameStats {
    /// MB/s implied by the fastest single frame.
    pub fn fastest_mb_per_sec(&self, frame_bytes: u64) -> f64 {
        rate_mb_s(frame_bytes, self.fastest_ms)
    }
    /// MB/s implied by the slowest single frame — the worst-case number.
    pub fn slowest_mb_per_sec(&self, frame_bytes: u64) -> f64 {
        rate_mb_s(frame_bytes, self.slowest_ms)
    }
}

fn rate_mb_s(bytes: u64, ms: f64) -> f64 {
    if ms <= 0.0 {
        return 0.0;
    }
    (bytes as f64 / (1024.0 * 1024.0)) / (ms / 1000.0)
}

/// Final aggregate for a completed run.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RunSummary {
    pub run_id: String,
    pub avg_mbps: f64,
    pub peak_mbps: f64,
    pub bytes_total: u64,
    pub p50_rtt_ms: f64,
    pub p95_rtt_ms: f64,
    pub p99_rtt_ms: f64,
    pub retransmits: u64,
    pub sack_active: bool,
    pub phases: LatencyPhases,
    /// frametest report for multi-file runs; `None` for large-file runs.
    #[serde(default)]
    pub frame: Option<FrameStats>,
    /// Final state of this end's lifecycle lanes. Repeats the last live
    /// `Phase` update for each lane so a run reloaded from history renders the
    /// same Gantt as one watched live.
    #[serde(default)]
    pub lanes: Vec<LaneUpdate>,
}

/// Messages the agent sends up to the console.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "camelCase", rename_all_fields = "camelCase")]
pub enum AgentMsg {
    Register {
        agent_id: String,
        name: String,
        os: String,
        arch: String,
        /// host:port other agents can reach this one on for the data plane.
        data_addr: String,
        capabilities: Capabilities,
    },
    Heartbeat {
        agent_id: String,
    },
    /// Receiver is listening and ready; console relays `listen_addr` to the sender.
    ReceiveReady {
        run_id: String,
        listen_addr: String,
    },
    Telemetry(TelemetrySample),
    /// Live lifecycle-lane progress, so the Gantt fills in during the run.
    Phase(LaneUpdate),
    RunComplete {
        summary: RunSummary,
    },
    RunError {
        run_id: String,
        message: String,
    },
    Log {
        agent_id: String,
        level: String,
        message: String,
    },
}

/// Messages the console sends down to the agent.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "camelCase", rename_all_fields = "camelCase")]
pub enum ConsoleMsg {
    /// Become the receiver for this run: bind a data-plane listener and reply ReceiveReady.
    PrepareReceive {
        run_id: String,
        scenario: Scenario,
    },
    /// Become the sender: connect to `target_addr` and run the scenario.
    StartSend {
        run_id: String,
        scenario: Scenario,
        target_addr: String,
    },
    Abort {
        run_id: String,
    },
    Pong,
}

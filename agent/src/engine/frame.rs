//! Multi-file transfer mode — DVS `frametest` semantics, end to end.
//!
//! Large-file mode asks "how fast is this link?". This module asks the question
//! the user actually cares about: **how much of that do you keep when the payload
//! is three thousand discrete frame files instead of one stream?** Every frame
//! pays open + I/O + close on the source, crosses the wire with a header, then
//! pays open + I/O + close again on the sink. Those costs are measured separately
//! so the Gantt can show whether you are losing time to the disk or to the wire.
//!
//! What makes this frametest rather than merely "lots of small writes" is the
//! **playback model**: frames are paced against an fps deadline (`-f`) through a
//! bounded queue (`-q`), and a frame that comes due while the queue is full is
//! *dropped* — not delayed. Dropped frames are excluded from the transferred
//! count, which is why a real frametest run of 1800 frames reports 1796. That
//! number is the one that says "this pipeline cannot sustain playback".
//!
//! Timing vocabulary matches the original's report, so numbers here are directly
//! comparable to numbers from a real frametest run:
//!   * *create* — open/create the file
//!   * *io*     — the data transfer itself
//!   * *close*  — close (and, for writes, the flush the close implies)
//!   * *file*   — the whole frame, create through close

use std::collections::VecDeque;
use std::io::{Read, Write};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant};

use anyhow::{bail, Context, Result};

use crate::protocol::{
    histogram_bucket, FrameMode, FrameOrder, FrameProgress, FrameSpec, FrameStats, FrameStorage,
    MinAvgMax, Scenario, WindowRow,
};

/// Per-frame wire header: `seq: u64, len: u32, flags: u32`, little-endian.
/// Deliberately minimal — there is no per-frame acknowledgement, because a
/// round-trip per frame would distort the very thing being measured. The sink
/// reports its own I/O timings through its own telemetry stream instead.
pub const FRAME_HEADER_LEN: usize = 16;

/// Alignment required for direct I/O buffers and lengths.
pub const DIRECT_ALIGN: u64 = 4096;

pub fn encode_header(seq: u64, len: u32, flags: u32) -> [u8; FRAME_HEADER_LEN] {
    let mut h = [0u8; FRAME_HEADER_LEN];
    h[0..8].copy_from_slice(&seq.to_le_bytes());
    h[8..12].copy_from_slice(&len.to_le_bytes());
    h[12..16].copy_from_slice(&flags.to_le_bytes());
    h
}

pub fn decode_header(h: &[u8; FRAME_HEADER_LEN]) -> (u64, u32, u32) {
    let seq = u64::from_le_bytes(h[0..8].try_into().unwrap());
    let len = u32::from_le_bytes(h[8..12].try_into().unwrap());
    let flags = u32::from_le_bytes(h[12..16].try_into().unwrap());
    (seq, len, flags)
}

// ---------------------------------------------------------------------------
// Statistics
// ---------------------------------------------------------------------------

/// A min/avg/max accumulator in nanoseconds, lock-free.
#[derive(Debug)]
pub struct StageStat {
    pub total_ns: AtomicU64,
    pub count: AtomicU64,
    pub min_ns: AtomicU64,
    pub max_ns: AtomicU64,
}

impl Default for StageStat {
    fn default() -> Self {
        Self {
            total_ns: AtomicU64::new(0),
            count: AtomicU64::new(0),
            min_ns: AtomicU64::new(u64::MAX),
            max_ns: AtomicU64::new(0),
        }
    }
}

impl StageStat {
    pub fn record(&self, ns: u64) {
        self.total_ns.fetch_add(ns, Ordering::Relaxed);
        self.count.fetch_add(1, Ordering::Relaxed);
        self.min_ns.fetch_min(ns, Ordering::Relaxed);
        self.max_ns.fetch_max(ns, Ordering::Relaxed);
    }

    pub fn snapshot(&self) -> MinAvgMax {
        let n = self.count.load(Ordering::Relaxed);
        let min = self.min_ns.load(Ordering::Relaxed);
        MinAvgMax {
            min_ms: if n == 0 { 0.0 } else { ns_ms(min) },
            avg_ms: if n == 0 {
                0.0
            } else {
                ns_ms(self.total_ns.load(Ordering::Relaxed) / n)
            },
            max_ms: ns_ms(self.max_ns.load(Ordering::Relaxed)),
        }
    }
}

fn ns_ms(ns: u64) -> f64 {
    ns as f64 / 1.0e6
}

/// One sampler tick, retained so the final report can compute frametest's
/// trailing 1s / 5s / 30s windows by differencing.
#[derive(Debug, Clone, Copy, Default)]
struct Tick {
    t_secs: f64,
    frames: u64,
    bytes: u64,
    open_ns: u64,
    io_ns: u64,
    frame_ns: u64,
}

/// Everything the frame path measures, shared between workers and the sampler.
#[derive(Debug)]
pub struct FrameCounters {
    pub frames_done: AtomicU64,
    pub frames_dropped: AtomicU64,
    pub bytes: AtomicU64,
    pub create: StageStat,
    pub io: StageStat,
    pub close: StageStat,
    /// Whole-frame time.
    pub file: StageStat,
    /// Time spent purely in filesystem calls (create + io + close). Tracked apart
    /// from `file` because in Memory storage mode `file` is still meaningful while
    /// this collapses to ~0 — that gap is what the Gantt's I/O band shows.
    pub fs_ns: AtomicU64,
    hist: [AtomicU64; 13],
    ticks: Mutex<Vec<Tick>>,
}

impl Default for FrameCounters {
    fn default() -> Self {
        Self {
            frames_done: AtomicU64::new(0),
            frames_dropped: AtomicU64::new(0),
            bytes: AtomicU64::new(0),
            create: StageStat::default(),
            io: StageStat::default(),
            close: StageStat::default(),
            file: StageStat::default(),
            fs_ns: AtomicU64::new(0),
            hist: Default::default(),
            ticks: Mutex::new(Vec::new()),
        }
    }
}

impl FrameCounters {
    pub fn new() -> Arc<Self> {
        Arc::new(Self::default())
    }

    /// Record one completed frame.
    pub fn record_frame(&self, t: &FrameTiming) {
        self.create.record(t.create_ns);
        self.io.record(t.io_ns);
        self.close.record(t.close_ns);
        self.file.record(t.total_ns);
        self.fs_ns
            .fetch_add(t.create_ns + t.io_ns + t.close_ns, Ordering::Relaxed);
        self.bytes.fetch_add(t.bytes, Ordering::Relaxed);
        self.frames_done.fetch_add(1, Ordering::Relaxed);
        let b = histogram_bucket(ns_ms(t.total_ns));
        self.hist[b].fetch_add(1, Ordering::Relaxed);
    }

    pub fn record_drop(&self) {
        self.frames_dropped.fetch_add(1, Ordering::Relaxed);
    }

    /// Called by the sampler once per interval; also returns the interval's
    /// frame progress for live telemetry.
    pub fn tick(&self, t_secs: f64) -> FrameProgress {
        let cur = Tick {
            t_secs,
            frames: self.frames_done.load(Ordering::Relaxed),
            bytes: self.bytes.load(Ordering::Relaxed),
            open_ns: self.create.total_ns.load(Ordering::Relaxed),
            io_ns: self.io.total_ns.load(Ordering::Relaxed),
            frame_ns: self.file.total_ns.load(Ordering::Relaxed),
        };
        let mut ticks = self.ticks.lock().unwrap();
        let prev = ticks.last().copied().unwrap_or_default();
        ticks.push(cur);
        drop(ticks);

        let dframes = cur.frames.saturating_sub(prev.frames);
        let dt = (cur.t_secs - prev.t_secs).max(1e-6);
        let per = |a: u64, b: u64| {
            if dframes == 0 {
                0.0
            } else {
                ns_ms(a.saturating_sub(b) / dframes)
            }
        };
        FrameProgress {
            fps: dframes as f64 / dt,
            frames_done: cur.frames,
            frames_dropped: self.frames_dropped.load(Ordering::Relaxed),
            frame_ms_avg: per(cur.frame_ns, prev.frame_ns),
            open_ms_avg: per(cur.open_ns, prev.open_ns),
            io_ms_avg: per(cur.io_ns, prev.io_ns),
            close_ms_avg: {
                let n = self.close.count.load(Ordering::Relaxed);
                if n == 0 {
                    0.0
                } else {
                    ns_ms(self.close.total_ns.load(Ordering::Relaxed) / n)
                }
            },
        }
    }

    /// Mean seconds of filesystem time per frame — feeds the Gantt I/O band.
    pub fn mean_fs_ms(&self) -> f64 {
        let n = self.frames_done.load(Ordering::Relaxed);
        if n == 0 {
            0.0
        } else {
            ns_ms(self.fs_ns.load(Ordering::Relaxed) / n)
        }
    }

    /// Build the final frametest report.
    pub fn finish(&self, elapsed_secs: f64) -> FrameStats {
        let file = self.file.snapshot();
        let hist: Vec<u64> = self.hist.iter().map(|a| a.load(Ordering::Relaxed)).collect();
        let ticks = self.ticks.lock().unwrap().clone();
        FrameStats {
            frames_transferred: self.frames_done.load(Ordering::Relaxed),
            frames_dropped: self.frames_dropped.load(Ordering::Relaxed),
            bytes_total: self.bytes.load(Ordering::Relaxed),
            fastest_ms: file.min_ms,
            slowest_ms: file.max_ms,
            file,
            create: self.create.snapshot(),
            io: self.io.snapshot(),
            close: self.close.snapshot(),
            histogram: hist,
            windows: build_windows(&ticks, elapsed_secs),
        }
    }
}

/// frametest's "Averaged details" table: trailing 1s / 5s / 30s plus Overall.
/// Each row differences the newest tick against the newest tick that is at least
/// `window` seconds older, so the row means exactly what its label says.
fn build_windows(ticks: &[Tick], elapsed_secs: f64) -> Vec<WindowRow> {
    let mut rows = Vec::new();
    let Some(last) = ticks.last().copied() else {
        return rows;
    };
    for (label, window) in [("1s", 1.0), ("5s", 5.0), ("30s", 30.0)] {
        let cutoff = last.t_secs - window;
        // Newest tick at or before the cutoff; falls back to the origin when the
        // run is shorter than the window, which makes short-run rows equal Overall
        // — the same thing the original does.
        let base = ticks
            .iter()
            .rev()
            .find(|t| t.t_secs <= cutoff)
            .copied()
            .unwrap_or_default();
        rows.push(window_row(label, base, last));
    }
    rows.push(window_row(
        "Overall",
        Tick::default(),
        Tick {
            t_secs: elapsed_secs.max(last.t_secs),
            ..last
        },
    ));
    rows
}

fn window_row(label: &str, a: Tick, b: Tick) -> WindowRow {
    let dframes = b.frames.saturating_sub(a.frames);
    let dt = (b.t_secs - a.t_secs).max(1e-6);
    let per = |x: u64, y: u64| {
        if dframes == 0 {
            0.0
        } else {
            ns_ms(x.saturating_sub(y) / dframes)
        }
    };
    WindowRow {
        label: label.to_string(),
        open_ms: per(b.open_ns, a.open_ns),
        io_ms: per(b.io_ns, a.io_ns),
        frame_ms: per(b.frame_ns, a.frame_ns),
        mb_per_sec: (b.bytes.saturating_sub(a.bytes) as f64 / (1024.0 * 1024.0)) / dt,
        fps: dframes as f64 / dt,
    }
}

/// Timings for a single frame.
#[derive(Debug, Clone, Copy, Default)]
pub struct FrameTiming {
    pub create_ns: u64,
    pub io_ns: u64,
    pub close_ns: u64,
    pub total_ns: u64,
    pub bytes: u64,
}

// ---------------------------------------------------------------------------
// Frame plan: which frames, in what order, at what paths
// ---------------------------------------------------------------------------

/// Resolves the frame set once so every worker agrees on it.
pub struct FramePlan {
    pub spec: FrameSpec,
    /// Frame indices in access order (`-v` reverse, `-m` random, else sequential).
    order: Vec<u64>,
    /// Filename stem and extension parsed from `--name`, or frametest's default.
    stem: String,
    ext: String,
    digits: usize,
    first: u64,
}

impl FramePlan {
    pub fn new(spec: FrameSpec) -> Result<Self> {
        if spec.frame_bytes == 0 {
            bail!("frame size must be greater than zero");
        }
        let total = spec.total_frames();
        if total == 0 {
            bail!("frame count must be greater than zero");
        }
        let mut order: Vec<u64> = (0..total).collect();
        match spec.order {
            FrameOrder::Sequential => {}
            FrameOrder::Reverse => order.reverse(),
            // Deterministic shuffle: a run must be reproducible, and pulling in a
            // full RNG for this would be overkill. This is a 64-bit LCG walk.
            FrameOrder::Random => {
                let mut state: u64 = 0x9E3779B97F4A7C15;
                for i in (1..order.len()).rev() {
                    state = state
                        .wrapping_mul(6364136223846793005)
                        .wrapping_add(1442695040888963407);
                    let j = (state >> 33) as usize % (i + 1);
                    order.swap(i, j);
                }
            }
        }
        let (stem, ext, digits, first) = parse_name(&spec.name_pattern);
        Ok(Self {
            spec,
            order,
            stem,
            ext,
            digits,
            first,
        })
    }

    pub fn total(&self) -> u64 {
        self.order.len() as u64
    }

    /// The frame index to process at position `pos` in the access order.
    pub fn at(&self, pos: u64) -> u64 {
        self.order[(pos as usize) % self.order.len()]
    }

    /// Path for a frame index, honouring `-d` (files per directory).
    pub fn path_for(&self, root: &str, idx: u64) -> std::path::PathBuf {
        let mut p = std::path::PathBuf::from(root);
        if self.spec.files_per_dir > 0 {
            p.push(format!("dir{:05}", idx / self.spec.files_per_dir as u64));
        }
        p.push(format!(
            "{}{:0width$}{}",
            self.stem,
            self.first + idx,
            self.ext,
            width = self.digits
        ));
        p
    }
}

/// Split a `--name` pattern into stem / counter width / extension.
///
/// frametest names the first file and counts up from it, so `shot-172.000001.exr`
/// implies stem `shot-172.`, six digits starting at 1, extension `.exr`. With no
/// pattern we use frametest's own `frame%06u.tst`.
fn parse_name(pattern: &str) -> (String, String, usize, u64) {
    if pattern.is_empty() {
        return ("frame".into(), ".tst".into(), 6, 0);
    }
    let (base, ext) = match pattern.rfind('.') {
        Some(i) if i > 0 => (&pattern[..i], &pattern[i..]),
        _ => (pattern, ""),
    };
    // Trailing run of digits is the counter.
    let digits = base.chars().rev().take_while(|c| c.is_ascii_digit()).count();
    if digits == 0 {
        return (base.to_string(), ext.to_string(), 6, 0);
    }
    let split = base.len() - digits;
    let first = base[split..].parse::<u64>().unwrap_or(0);
    (base[..split].to_string(), ext.to_string(), digits, first)
}

// ---------------------------------------------------------------------------
// Direct I/O
// ---------------------------------------------------------------------------

/// A page-aligned buffer, required for direct I/O on every platform that has it.
pub struct AlignedBuf {
    ptr: *mut u8,
    len: usize,
    layout: std::alloc::Layout,
}

// The raw pointer is owned exclusively by this struct and never aliased.
unsafe impl Send for AlignedBuf {}
unsafe impl Sync for AlignedBuf {}

impl AlignedBuf {
    pub fn new(len: usize) -> Self {
        let len = len.max(1);
        let layout = std::alloc::Layout::from_size_align(len, DIRECT_ALIGN as usize)
            .expect("valid aligned layout");
        // SAFETY: layout has non-zero size and a valid power-of-two alignment.
        let ptr = unsafe { std::alloc::alloc(layout) };
        assert!(!ptr.is_null(), "out of memory allocating frame buffer");
        // Fill with a recognisable pattern rather than leaving it uninitialised.
        unsafe { std::ptr::write_bytes(ptr, 0xAB, len) };
        Self { ptr, len, layout }
    }

    pub fn as_slice(&self) -> &[u8] {
        unsafe { std::slice::from_raw_parts(self.ptr, self.len) }
    }

    pub fn as_mut_slice(&mut self) -> &mut [u8] {
        unsafe { std::slice::from_raw_parts_mut(self.ptr, self.len) }
    }

    pub fn len(&self) -> usize {
        self.len
    }
}

impl Drop for AlignedBuf {
    fn drop(&mut self) {
        unsafe { std::alloc::dealloc(self.ptr, self.layout) };
    }
}

/// Open a frame file, bypassing the page cache when `direct` is set.
///
/// The original frametest's buffering mode is undocumented. We default to direct
/// I/O because every one of its frame sizes is an exact 4096 multiple (which is
/// what direct I/O alignment demands) and because the tframetest clone uses it
/// unconditionally — but that is inference, not documentation, so `-`-style
/// buffered operation stays available via `FrameSpec::direct_io`.
pub fn open_frame(path: &std::path::Path, write: bool, direct: bool) -> Result<std::fs::File> {
    let mut opts = std::fs::OpenOptions::new();
    if write {
        opts.write(true).create(true).truncate(true);
    } else {
        opts.read(true);
    }

    #[cfg(target_os = "linux")]
    if direct {
        use std::os::unix::fs::OpenOptionsExt;
        opts.custom_flags(libc::O_DIRECT);
    }

    #[cfg(windows)]
    if direct {
        use std::os::windows::fs::OpenOptionsExt;
        const FILE_FLAG_NO_BUFFERING: u32 = 0x2000_0000;
        const FILE_FLAG_WRITE_THROUGH: u32 = 0x8000_0000;
        opts.custom_flags(FILE_FLAG_NO_BUFFERING | FILE_FLAG_WRITE_THROUGH);
    }

    if write {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent).ok();
        }
    }
    let file = opts
        .open(path)
        .with_context(|| format!("open frame {}", path.display()))?;

    // macOS has no O_DIRECT; F_NOCACHE is the equivalent and must be set after open.
    #[cfg(target_os = "macos")]
    if direct {
        use std::os::unix::io::AsRawFd;
        // SAFETY: fd is valid for the lifetime of `file`; F_NOCACHE takes an int.
        unsafe {
            libc::fcntl(file.as_raw_fd(), libc::F_NOCACHE, 1);
        }
    }

    let _ = direct; // used only on the cfg paths above
    Ok(file)
}

// ---------------------------------------------------------------------------
// Pacing and drop accounting
// ---------------------------------------------------------------------------

/// The playback queue between the pacer and the workers.
///
/// This is the heart of frametest's model. The pacer releases frame slots on the
/// fps deadline; if a slot comes due while `queue_depth` frames are already
/// waiting, that frame is **dropped** rather than delayed. Dropping (not
/// blocking) is what makes the transferred-frame count a real measure of whether
/// the pipeline sustains playback.
pub struct FrameQueue {
    inner: Mutex<VecDeque<u64>>,
    cv: Condvar,
    /// Async counterpart to `cv`, for the QUIC path whose workers are tasks
    /// rather than threads. Both are signalled on every offer.
    notify: tokio::sync::Notify,
    closed: AtomicBool,
    capacity: usize,
}

impl FrameQueue {
    pub fn new(capacity: usize) -> Arc<Self> {
        Arc::new(Self {
            inner: Mutex::new(VecDeque::new()),
            cv: Condvar::new(),
            notify: tokio::sync::Notify::new(),
            closed: AtomicBool::new(false),
            capacity,
        })
    }

    /// Offer a frame. Returns false when the queue was full — the caller counts
    /// that as a drop.
    pub fn offer(&self, pos: u64) -> bool {
        let mut q = self.inner.lock().unwrap();
        if self.capacity > 0 && q.len() >= self.capacity {
            return false;
        }
        q.push_back(pos);
        self.cv.notify_one();
        self.notify.notify_one();
        true
    }

    /// Async take, for task-based workers. Re-checks the queue after each wake,
    /// so a notification that races with a close or another taker is harmless.
    pub async fn take_async(&self) -> Option<u64> {
        loop {
            // Register interest *before* checking, so an offer that lands
            // between the check and the wait is not missed.
            let waiter = self.notify.notified();
            {
                let mut q = self.inner.lock().unwrap();
                if let Some(v) = q.pop_front() {
                    return Some(v);
                }
                if self.closed.load(Ordering::Relaxed) {
                    return None;
                }
            }
            waiter.await;
        }
    }

    /// Take the next frame, or `None` once the queue is closed and drained.
    pub fn take(&self) -> Option<u64> {
        let mut q = self.inner.lock().unwrap();
        loop {
            if let Some(v) = q.pop_front() {
                return Some(v);
            }
            if self.closed.load(Ordering::Relaxed) {
                return None;
            }
            q = self.cv.wait(q).unwrap();
        }
    }

    pub fn close(&self) {
        self.closed.store(true, Ordering::Relaxed);
        self.cv.notify_all();
        self.notify.notify_waiters();
        // A waiter that has not yet parked still needs a permit to consume.
        self.notify.notify_one();
    }

    /// Depth right now. Only the drop-accounting tests need this — the engine
    /// itself never inspects the queue, it just offers and takes.
    #[cfg(test)]
    pub fn len(&self) -> usize {
        self.inner.lock().unwrap().len()
    }
}

/// Run the pacer: release frames on the `-f` deadline, staging `-b` frames first,
/// counting a drop whenever the queue is full at the deadline.
///
/// Returns once every frame has been offered (or dropped).
pub fn run_pacer(
    plan: Arc<FramePlan>,
    queue: Arc<FrameQueue>,
    counters: Arc<FrameCounters>,
    stop: crate::engine::Stop,
) {
    let total = plan.total();
    let fps = plan.spec.fps_limit;
    let period = if fps > 0.0 {
        Some(Duration::from_secs_f64(1.0 / fps))
    } else {
        None
    };

    // Pre-buffer: stage frames before the clock starts, so the run measures
    // steady-state playback rather than the cold start.
    let prebuf = (plan.spec.prebuffer as u64).min(total);
    let mut pos = 0u64;
    while pos < prebuf {
        if !queue.offer(pos) {
            break;
        }
        pos += 1;
    }

    let start = Instant::now();
    while pos < total {
        if stop.load(Ordering::Relaxed) {
            break;
        }
        if let Some(period) = period {
            // Absolute deadline from the run start, so pacing does not drift.
            let due = period.mul_f64((pos - prebuf) as f64);
            let now = start.elapsed();
            if due > now {
                std::thread::sleep(due - now);
            }
        }
        if !queue.offer(pos) {
            counters.record_drop();
        }
        pos += 1;
    }
    queue.close();
}

// ---------------------------------------------------------------------------
// Source side
// ---------------------------------------------------------------------------

/// Produce one frame's payload, timing the filesystem work.
///
/// In `Disk` storage the frame is read from (or created on) the real filesystem.
/// In `Memory` storage the buffer is reused and the create/io/close timings stay
/// near zero — which is exactly how you tell disk cost apart from wire cost.
pub fn load_frame(
    plan: &FramePlan,
    idx: u64,
    buf: &mut AlignedBuf,
    t: &mut FrameTiming,
) -> Result<usize> {
    let spec = &plan.spec;
    if matches!(spec.mode, FrameMode::Empty) {
        // `-e`: open/close only, no payload. Isolates metadata cost.
        if matches!(spec.storage, FrameStorage::Disk) {
            let path = plan.path_for(&spec.path, idx);
            let t0 = Instant::now();
            let f = open_frame(&path, true, false)?;
            t.create_ns = t0.elapsed().as_nanos() as u64;
            let t1 = Instant::now();
            drop(f);
            t.close_ns = t1.elapsed().as_nanos() as u64;
        }
        return Ok(0);
    }

    let want = spec.cropped_bytes() as usize;
    let want = want.min(buf.len());

    if matches!(spec.storage, FrameStorage::Memory) {
        // Buffer already holds a payload pattern; nothing to do but report it.
        return Ok(want);
    }

    let path = plan.path_for(&spec.path, idx);
    let ios = spec.ios_per_frame.max(1) as usize;

    match spec.mode {
        FrameMode::Write => {
            let t0 = Instant::now();
            let mut f = open_frame(&path, true, spec.direct_io)?;
            t.create_ns = t0.elapsed().as_nanos() as u64;

            // `-i`: split the transfer into N calls, as the original does.
            let total = spec.frame_bytes as usize;
            let chunk = align_chunk(total / ios, spec.direct_io);
            let t1 = Instant::now();
            let mut written = 0usize;
            while written < total {
                let n = chunk.min(total - written).min(buf.len());
                f.write_all(&buf.as_slice()[..n])?;
                written += n;
            }
            t.io_ns = t1.elapsed().as_nanos() as u64;

            let t2 = Instant::now();
            drop(f);
            t.close_ns = t2.elapsed().as_nanos() as u64;
            Ok(want)
        }
        FrameMode::Read => {
            let t0 = Instant::now();
            let mut f = open_frame(&path, false, spec.direct_io)?;
            t.create_ns = t0.elapsed().as_nanos() as u64;

            // Skip the header, then read the (possibly cropped) payload from the
            // middle of the frame — `-c` reads the centre, not the front.
            let header = spec.header_kb as u64 * 1024;
            let offset = header + centre_offset(spec);
            let t1 = Instant::now();
            use std::io::Seek;
            f.seek(std::io::SeekFrom::Start(align_down(offset, spec.direct_io)))?;
            let mut read = 0usize;
            let chunk = align_chunk(want / ios, spec.direct_io);
            while read < want {
                let n = chunk.min(want - read);
                match f.read(&mut buf.as_mut_slice()[..n]) {
                    Ok(0) => break, // short file; report what we got
                    Ok(got) => read += got,
                    Err(e) => return Err(e).context("read frame"),
                }
            }
            t.io_ns = t1.elapsed().as_nanos() as u64;

            let t2 = Instant::now();
            drop(f);
            t.close_ns = t2.elapsed().as_nanos() as u64;
            Ok(read)
        }
        FrameMode::Empty => unreachable!("handled above"),
    }
}

/// `load_frame` for async callers: the blocking filesystem work runs on the
/// blocking pool so it never stalls the reactor. The buffer is moved in and back
/// out rather than shared, which keeps it single-owner without a lock.
pub async fn load_frame_async(
    plan: Arc<FramePlan>,
    idx: u64,
    mut buf: AlignedBuf,
) -> (AlignedBuf, Result<usize>, FrameTiming) {
    let handle = tokio::task::spawn_blocking(move || {
        let mut t = FrameTiming::default();
        let r = load_frame(&plan, idx, &mut buf, &mut t);
        (buf, r, t)
    });
    match handle.await {
        Ok(v) => v,
        // The blocking pool only fails if the runtime is shutting down.
        Err(e) => (
            AlignedBuf::new(1),
            Err(anyhow::anyhow!("frame load task failed: {e}")),
            FrameTiming::default(),
        ),
    }
}

/// Byte offset of the cropped region's start within the payload (`-c` reads the
/// middle N%, so the offset is half of what is skipped).
fn centre_offset(spec: &FrameSpec) -> u64 {
    let full = spec.payload_bytes();
    let cropped = spec.cropped_bytes();
    (full.saturating_sub(cropped)) / 2
}

fn align_chunk(n: usize, direct: bool) -> usize {
    if !direct {
        return n.max(1);
    }
    let a = DIRECT_ALIGN as usize;
    ((n.max(a) + a - 1) / a) * a
}

fn align_down(n: u64, direct: bool) -> u64 {
    if !direct {
        return n;
    }
    (n / DIRECT_ALIGN) * DIRECT_ALIGN
}

/// The source-side worker body for multi-file mode: pull frames from the queue,
/// load each from storage, and push it down the connection with its header.
///
/// `send` is the transport hook, so TCP/TLS, UDP and QUIC all reuse this loop.
pub fn source_frame_worker<W: Write>(
    plan: Arc<FramePlan>,
    queue: Arc<FrameQueue>,
    counters: Arc<FrameCounters>,
    net: Arc<crate::engine::Counters>,
    idx: usize,
    mut send: W,
    stop: crate::engine::Stop,
) {
    let mut buf = AlignedBuf::new(plan.spec.frame_bytes.max(1) as usize);
    while !stop.load(Ordering::Relaxed) {
        let Some(pos) = queue.take() else { break };
        let frame_idx = plan.at(pos);

        let whole = Instant::now();
        let mut t = FrameTiming::default();
        let payload = match load_frame(&plan, frame_idx, &mut buf, &mut t) {
            Ok(n) => n,
            Err(e) => {
                tracing::warn!("frame {frame_idx}: {e:#}");
                counters.record_drop();
                continue;
            }
        };

        // Header then payload. Both count toward wire bytes; only the payload
        // counts as frame bytes, so goodput stays honest.
        let hdr = encode_header(frame_idx, payload as u32, 0);
        if send.write_all(&hdr).is_err() {
            break;
        }
        if payload > 0 && send.write_all(&buf.as_slice()[..payload]).is_err() {
            break;
        }
        t.total_ns = whole.elapsed().as_nanos() as u64;
        t.bytes = payload as u64;

        counters.record_frame(&t);
        net.add(idx, (payload + FRAME_HEADER_LEN) as u64);
    }
    let _ = send.flush();
}

// ---------------------------------------------------------------------------
// Sink side
// ---------------------------------------------------------------------------

/// The sink-side worker body: read header + payload, then store the frame,
/// timing the filesystem work separately so the console can attribute it.
pub fn sink_frame_worker<R: Read>(
    spec: Arc<FrameSpec>,
    plan: Arc<FramePlan>,
    counters: Arc<FrameCounters>,
    net: Arc<crate::engine::Counters>,
    idx: usize,
    mut recv: R,
    stop: crate::engine::Stop,
) {
    let root = if spec.sink_path.is_empty() {
        spec.path.clone()
    } else {
        spec.sink_path.clone()
    };
    let cap = spec.frame_bytes.max(1) as usize;
    let mut buf = AlignedBuf::new(cap);

    while !stop.load(Ordering::Relaxed) {
        let mut hdr = [0u8; FRAME_HEADER_LEN];
        if recv.read_exact(&mut hdr).is_err() {
            break;
        }
        let (seq, len, _flags) = decode_header(&hdr);
        let len = (len as usize).min(cap);

        let whole = Instant::now();
        if len > 0 && recv.read_exact(&mut buf.as_mut_slice()[..len]).is_err() {
            break;
        }

        let mut t = FrameTiming::default();
        if matches!(spec.storage, FrameStorage::Disk) {
            let path = plan.path_for(&root, seq);
            match write_frame_file(&path, &buf.as_slice()[..len], &spec, &mut t) {
                Ok(()) => {}
                Err(e) => {
                    tracing::warn!("sink frame {seq}: {e:#}");
                    counters.record_drop();
                    continue;
                }
            }
        }
        // Memory storage: the frame is discarded here on purpose. That is the
        // point of the mode — it removes the sink's disk from the measurement.

        t.total_ns = whole.elapsed().as_nanos() as u64;
        t.bytes = len as u64;
        counters.record_frame(&t);
        net.add(idx, (len + FRAME_HEADER_LEN) as u64);
    }
}

/// Store one received frame, timing create / write / close separately. Shared by
/// the TCP and QUIC sinks.
pub fn write_frame_file(
    path: &std::path::Path,
    data: &[u8],
    spec: &FrameSpec,
    t: &mut FrameTiming,
) -> Result<()> {
    let t0 = Instant::now();
    let mut f = open_frame(path, true, spec.direct_io)?;
    t.create_ns = t0.elapsed().as_nanos() as u64;

    let t1 = Instant::now();
    if !data.is_empty() {
        f.write_all(data)?;
    }
    t.io_ns = t1.elapsed().as_nanos() as u64;

    let t2 = Instant::now();
    drop(f);
    t.close_ns = t2.elapsed().as_nanos() as u64;
    Ok(())
}

// ---------------------------------------------------------------------------
// Scenario validation
// ---------------------------------------------------------------------------

/// Reject an unusable multi-file scenario up front, so the console gets one
/// clear reason instead of a failure buried in the datapath.
pub fn validate(sc: &Scenario) -> Result<Arc<FramePlan>> {
    let Some(spec) = sc.frame.clone() else {
        bail!("multi-file mode requires a frame specification");
    };
    if matches!(spec.storage, FrameStorage::Disk) {
        if spec.path.is_empty() {
            bail!("multi-file mode with Disk storage requires a path");
        }
        let p = std::path::Path::new(&spec.path);
        if matches!(spec.mode, FrameMode::Read) && !p.is_dir() {
            bail!("read mode needs an existing directory of frames: {}", spec.path);
        }
        if !matches!(spec.mode, FrameMode::Read) {
            std::fs::create_dir_all(p)
                .with_context(|| format!("create frame directory {}", spec.path))?;
        }
    }
    if spec.async_depth > 0 && !matches!(spec.storage, FrameStorage::Disk) {
        // -a describes overlapped *file* I/O; with no file there is nothing to
        // overlap. Say so rather than silently ignoring the flag.
        bail!("async I/O depth (-a) requires Disk storage");
    }
    Ok(Arc::new(FramePlan::new(spec)?))
}

/// Sleep out `-p` before the run starts, letting caches settle.
pub fn apply_pause(spec: &FrameSpec) {
    if spec.pause_secs > 0 {
        std::thread::sleep(Duration::from_secs(spec.pause_secs as u64));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::{preset_payload_bytes, HISTOGRAM_EDGES_MS};

    fn spec(bytes: u64) -> FrameSpec {
        FrameSpec {
            mode: FrameMode::Write,
            frame_bytes: bytes,
            frame_count: 10,
            fps_limit: 0.0,
            queue_depth: 0,
            prebuffer: 5,
            order: FrameOrder::Sequential,
            storage: FrameStorage::Memory,
            path: String::new(),
            sink_path: String::new(),
            header_kb: 64,
            files_per_dir: 0,
            name_pattern: String::new(),
            async_depth: 0,
            out_of_order: false,
            loop_frames: 0,
            ios_per_frame: 1,
            crop_percent: 0,
            pause_secs: 0,
            prealloc: 0,
            direct_io: true,
        }
    }

    /// The two presets we could verify byte-exactly against both the DVS
    /// reference output and the tframetest clone. If these ever drift, our
    /// numbers stop being comparable to a real frametest run.
    #[test]
    fn preset_sizes_are_byte_exact() {
        let header = 64 * 1024;
        assert_eq!(preset_payload_bytes("2k").unwrap(), 12_746_752);
        assert_eq!(preset_payload_bytes("2k").unwrap() + header, 12_812_288);
        assert_eq!(preset_payload_bytes("4k").unwrap(), 50_987_008);
        assert_eq!(preset_payload_bytes("4k").unwrap() + header, 51_052_544);
    }

    /// Every preset must be direct-I/O aligned, payload and whole file alike;
    /// that alignment is the main evidence the original uses direct I/O at all.
    #[test]
    fn presets_are_direct_io_aligned() {
        for name in ["sd", "hd", "2k", "4k"] {
            let payload = preset_payload_bytes(name).unwrap();
            assert_eq!(payload % DIRECT_ALIGN, 0, "{name} payload is not 4096-aligned");
            assert_eq!((payload + 64 * 1024) % DIRECT_ALIGN, 0, "{name} file is not aligned");
        }
        // sd is the only geometry that needs the rounding; this is the value the
        // tframetest clone produces.
        assert_eq!(preset_payload_bytes("sd").unwrap(), 1_384_448);
        assert_eq!(preset_payload_bytes("hd").unwrap(), 8_294_400);
    }

    #[test]
    fn histogram_buckets_match_frametest_edges() {
        assert_eq!(histogram_bucket(0.05), 0); // <0.1
        assert_eq!(histogram_bucket(0.15), 1); // .2
        assert_eq!(histogram_bucket(0.3), 2); // .5
        assert_eq!(histogram_bucket(0.9), 3); // 1
        assert_eq!(histogram_bucket(1.5), 4); // 2
        assert_eq!(histogram_bucket(24.09), 8); // 50 — the reference run's average
        assert_eq!(histogram_bucket(229.31), 11); // 500 — its slowest frame
        assert_eq!(histogram_bucket(5000.0), 12); // >1s
        assert_eq!(histogram_bucket(0.0), 0);
    }

    #[test]
    fn histogram_has_one_bucket_per_label() {
        assert_eq!(HISTOGRAM_EDGES_MS.len() + 1, 13);
    }

    /// A full queue at the deadline drops the frame instead of delaying it —
    /// the behaviour that makes the transferred count meaningful.
    #[test]
    fn full_queue_drops_rather_than_blocks() {
        let q = FrameQueue::new(2);
        assert!(q.offer(0));
        assert!(q.offer(1));
        assert!(!q.offer(2), "third offer must be refused, not queued");
        assert_eq!(q.len(), 2);
        assert_eq!(q.take(), Some(0));
        assert!(q.offer(2), "space freed, offer accepted again");
    }

    #[test]
    fn zero_capacity_queue_is_unbounded() {
        let q = FrameQueue::new(0);
        for i in 0..1000 {
            assert!(q.offer(i));
        }
        assert_eq!(q.len(), 1000);
    }

    #[test]
    fn closed_queue_drains_then_ends() {
        let q = FrameQueue::new(4);
        q.offer(7);
        q.close();
        assert_eq!(q.take(), Some(7));
        assert_eq!(q.take(), None);
    }

    #[test]
    fn pacer_drops_when_workers_never_consume() {
        let mut s = spec(4096);
        s.frame_count = 20;
        s.fps_limit = 1000.0;
        s.queue_depth = 3;
        s.prebuffer = 0;
        let plan = Arc::new(FramePlan::new(s).unwrap());
        let q = FrameQueue::new(3);
        let c = FrameCounters::new();
        let stop = Arc::new(AtomicBool::new(false));
        // Nothing consumes, so everything past the queue's capacity must drop.
        run_pacer(plan, q.clone(), c.clone(), stop);
        assert_eq!(q.len(), 3);
        assert_eq!(c.frames_dropped.load(Ordering::Relaxed), 17);
    }

    #[test]
    fn access_order_respects_reverse_and_random() {
        let mut s = spec(4096);
        s.frame_count = 5;

        s.order = FrameOrder::Sequential;
        let p = FramePlan::new(s.clone()).unwrap();
        assert_eq!((0..5).map(|i| p.at(i)).collect::<Vec<_>>(), vec![0, 1, 2, 3, 4]);

        s.order = FrameOrder::Reverse;
        let p = FramePlan::new(s.clone()).unwrap();
        assert_eq!((0..5).map(|i| p.at(i)).collect::<Vec<_>>(), vec![4, 3, 2, 1, 0]);

        s.order = FrameOrder::Random;
        let p = FramePlan::new(s.clone()).unwrap();
        let mut got: Vec<u64> = (0..5).map(|i| p.at(i)).collect();
        got.sort();
        assert_eq!(got, vec![0, 1, 2, 3, 4], "shuffle must be a permutation");
    }

    #[test]
    fn loop_flag_multiplies_the_frame_set() {
        let mut s = spec(4096);
        s.frame_count = 4;
        s.loop_frames = 2; // the set plus two more passes
        assert_eq!(s.total_frames(), 12);
        assert_eq!(FramePlan::new(s).unwrap().total(), 12);
    }

    #[test]
    fn default_filenames_match_frametest() {
        let p = FramePlan::new(spec(4096)).unwrap();
        assert!(p.path_for("/tmp/x", 0).ends_with("frame000000.tst"));
        assert!(p.path_for("/tmp/x", 42).ends_with("frame000042.tst"));
    }

    #[test]
    fn name_pattern_preserves_width_and_start() {
        let mut s = spec(4096);
        s.name_pattern = "shot-172.000001.exr".into();
        let p = FramePlan::new(s).unwrap();
        // Counter is the trailing digit run; the stem keeps everything before it.
        assert!(p.path_for("/m", 0).ends_with("shot-172.000001.exr"));
        assert!(p.path_for("/m", 2).ends_with("shot-172.000003.exr"));
    }

    #[test]
    fn files_per_dir_spreads_across_subdirectories() {
        let mut s = spec(4096);
        s.files_per_dir = 100;
        let p = FramePlan::new(s).unwrap();
        assert!(p.path_for("/m", 0).to_string_lossy().contains("dir00000"));
        assert!(p.path_for("/m", 150).to_string_lossy().contains("dir00001"));
    }

    #[test]
    fn crop_reads_the_middle_of_the_payload() {
        let mut s = spec(1024 * 1024 + 64 * 1024);
        s.crop_percent = 50;
        assert_eq!(s.cropped_bytes(), s.payload_bytes() / 2);
        // Centred: what's skipped is split evenly before and after.
        assert_eq!(centre_offset(&s), s.payload_bytes() / 4);

        s.crop_percent = 100;
        assert_eq!(s.cropped_bytes(), s.payload_bytes());
        assert_eq!(centre_offset(&s), 0);
    }

    #[test]
    fn header_is_excluded_from_payload() {
        let s = spec(preset_payload_bytes("2k").unwrap() + 64 * 1024);
        assert_eq!(s.payload_bytes(), 12_746_752);
    }

    #[test]
    fn frame_header_round_trips() {
        let h = encode_header(u64::MAX, 51_052_544, 3);
        assert_eq!(decode_header(&h), (u64::MAX, 51_052_544, 3));
    }

    #[test]
    fn aligned_buffer_is_page_aligned() {
        let b = AlignedBuf::new(12_812_288);
        assert_eq!(b.as_slice().as_ptr() as usize % DIRECT_ALIGN as usize, 0);
        assert_eq!(b.len(), 12_812_288);
    }

    #[test]
    fn stage_stat_tracks_min_avg_max() {
        let s = StageStat::default();
        for ns in [1_000_000u64, 3_000_000, 2_000_000] {
            s.record(ns);
        }
        let m = s.snapshot();
        assert_eq!(m.min_ms, 1.0);
        assert_eq!(m.avg_ms, 2.0);
        assert_eq!(m.max_ms, 3.0);
    }

    #[test]
    fn empty_stage_stat_reports_zero_not_max() {
        let m = StageStat::default().snapshot();
        assert_eq!((m.min_ms, m.avg_ms, m.max_ms), (0.0, 0.0, 0.0));
    }

    /// Round-trip a real frame through the filesystem and back, and confirm the
    /// stage timings are attributed and the payload survives.
    #[test]
    fn disk_write_then_read_round_trips() {
        let dir = std::env::temp_dir().join(format!("bwft-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();

        let mut s = spec(64 * 1024 + 4096);
        s.storage = FrameStorage::Disk;
        s.path = dir.to_string_lossy().into();
        s.direct_io = false; // tmpdir may not support direct I/O
        s.frame_count = 1;

        let plan = FramePlan::new(s.clone()).unwrap();
        let mut buf = AlignedBuf::new(s.frame_bytes as usize);
        let mut t = FrameTiming::default();
        load_frame(&plan, 0, &mut buf, &mut t).unwrap();
        assert!(plan.path_for(&s.path, 0).exists());
        assert_eq!(
            std::fs::metadata(plan.path_for(&s.path, 0)).unwrap().len(),
            s.frame_bytes
        );

        let mut rs = s.clone();
        rs.mode = FrameMode::Read;
        let rplan = FramePlan::new(rs.clone()).unwrap();
        let mut rt = FrameTiming::default();
        let n = load_frame(&rplan, 0, &mut buf, &mut rt).unwrap();
        assert_eq!(n as u64, rs.cropped_bytes());
        assert!(rt.create_ns > 0 && rt.close_ns > 0);

        std::fs::remove_dir_all(&dir).ok();
    }

    /// `-e` moves no payload but must still pay (and report) open/close.
    #[test]
    fn empty_mode_transfers_no_payload() {
        let dir = std::env::temp_dir().join(format!("bwft-e-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();

        let mut s = spec(4096);
        s.mode = FrameMode::Empty;
        s.storage = FrameStorage::Disk;
        s.path = dir.to_string_lossy().into();
        s.direct_io = false;

        let plan = FramePlan::new(s.clone()).unwrap();
        let mut buf = AlignedBuf::new(4096);
        let mut t = FrameTiming::default();
        assert_eq!(load_frame(&plan, 0, &mut buf, &mut t).unwrap(), 0);
        assert_eq!(t.io_ns, 0, "empty mode does no data transfer");
        assert!(plan.path_for(&s.path, 0).exists());

        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn counters_build_a_frametest_report() {
        let c = FrameCounters::new();
        for ms in [10.0f64, 20.0, 30.0] {
            c.record_frame(&FrameTiming {
                create_ns: 1_000_000,
                io_ns: (ms * 1.0e6) as u64,
                close_ns: 500_000,
                total_ns: ((ms + 1.5) * 1.0e6) as u64,
                bytes: 1024 * 1024,
            });
        }
        c.record_drop();
        c.tick(1.0);
        let st = c.finish(1.0);
        assert_eq!(st.frames_transferred, 3);
        assert_eq!(st.frames_dropped, 1);
        assert_eq!(st.bytes_total, 3 * 1024 * 1024);
        assert_eq!(st.fastest_ms, 11.5);
        assert_eq!(st.slowest_ms, 31.5);
        assert_eq!(st.histogram.len(), 13);
        assert_eq!(st.histogram.iter().sum::<u64>(), 3);
        assert_eq!(st.windows.last().unwrap().label, "Overall");
        // Mean filesystem time per frame: 1.0 create + 20.0 io + 0.5 close.
        assert!((c.mean_fs_ms() - 21.5).abs() < 0.01);
    }

    #[test]
    fn multi_file_validation_rejects_missing_pieces() {
        let mut sc: Scenario = serde_json::from_str(
            r#"{"protocol":"Tcp","architecture":"Selector","threads":1,"processes":1,
                "dscp":0,"dscpEnabled":false,"payloadBytes":65536,"targetMbps":0,
                "durationSecs":5,"transferMode":"MultiFile"}"#,
        )
        .unwrap();
        assert!(validate(&sc).is_err(), "no frame spec must be rejected");

        let mut s = spec(4096);
        s.storage = FrameStorage::Disk;
        s.path = String::new();
        sc.frame = Some(s);
        assert!(validate(&sc).is_err(), "Disk storage with no path must be rejected");

        let mut s2 = spec(4096);
        s2.async_depth = 4; // Memory storage: nothing to overlap
        sc.frame = Some(s2);
        assert!(validate(&sc).is_err(), "-a without Disk storage must be rejected");
    }
}

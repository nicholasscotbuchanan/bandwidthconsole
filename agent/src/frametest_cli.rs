//! `bwagent frametest` — a drop-in for DVS frametest.
//!
//! Same flags, same report, run locally against a path. Existing frametest
//! command lines work verbatim, which matters because the point of this tool is
//! to *replace* frametest for people who already have scripts driving it.
//!
//! Flag semantics are taken from the DVS documentation and are deliberately
//! literal, including the parts that are easy to get wrong:
//!   * `-o` is out-of-order I/O completion, **not** an output file. CSV is `-x`.
//!   * `-v` is reverse access order, **not** verbose. Verbose is `--verbose`.
//!   * `-c` is crop percentage, **not** CSV.
//!   * `-h secs` sets the histogram window, so it is **not** help. The original
//!     prints help when given no arguments, and so do we.
//!   * `-w` still needs a size token even when `-z` supplies the real size, e.g.
//!     `frametest -w 1 -z 40000`.
//!
//! Arguments may be separated (`-n 1800`) or attached (`-n1800`) — the reference
//! output echoes `-w12512 -n1800`, so the attached form must parse.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Instant;

use anyhow::{bail, Context, Result};

use crate::engine::frame::{self, AlignedBuf, FrameCounters, FramePlan, FrameQueue, FrameTiming};
use crate::protocol::{
    preset_payload_bytes, FrameMode, FrameOrder, FrameSpec, FrameStats, FrameStorage,
    HISTOGRAM_LABELS,
};

const USAGE: &str = "\
Usage: bwagent frametest [options] path

Options:
  -w size   Perform write test with 'size' KB frames or \"sd\", \"hd\", \"2k\", \"4k\"
  -e        Perform write test with empty frames (open/close test)
  -r        Perform read test using existing frames (default)
  -s        Perform streaming test to/from a single file
  -n num    Number of frames to read or write (default = 1800)
  -a num    Use async I/O with 'num' overlapped operations (default is sync)
  -t num    Use multithreading I/O, with 'num' threads at one time
  -o        Handle completed I/Os out-of-order (not used in read and sync IO)
  -f rate   Limit speed to 'rate' frames per second
  -q num    Maximum 'num' of queued buffers before a frame is dropped
  -b num    Pre-buffer 'num' frames before starting \"playback\" (default = 5)
  -v        Create/access files in reverse order
  -m        Create/access files in a random pattern
  -l num    Number of frames to loop (default = 0: don't loop)
  -i num    Number of I/Os per frame (default = 1, ignored in async mode)
  -z size   Specifies a custom 'size' for read or write test in KB
  -d num    Use directories, with 'num' files per directory
  -p secs   Pause 'secs' before starting test
  -c perc   Crop mode, read middle 'perc'% of frame (ignored in write test)
  -u secs   Display update interval in seconds (default = 1s)
  -h secs   Histogram displays last 'secs' worth of info (default = all)
  -x file   Export detailed timing to a file (comma separated), append '.csv'
  --header=size   Header 'size' in KB for non-stream mode (default = 64)
  --name=name     Use 'name' as the filename pattern specifying the first file
  --noinfo        Don't display version and copyright information
  --prealloc=num  In streaming mode (-s), preallocate 'num' frames of space
  --verbose       Verbose/debugging mode

Extensions beyond the original:
  --buffered      Use the page cache instead of direct I/O
";

/// Everything the CLI collects, before it becomes a `FrameSpec`.
#[derive(Debug)]
pub struct Args {
    spec: FrameSpec,
    threads: u32,
    /// `-s`: stream to/from a single file rather than one file per frame.
    streaming: bool,
    /// `-u`: display update interval.
    update_secs: f64,
    /// `-h`: histogram window; `None` = the whole run.
    hist_window: Option<f64>,
    /// `-x`: CSV export path.
    csv: Option<String>,
    noinfo: bool,
    verbose: bool,
    /// The trailing positional: a directory, or a file in streaming mode.
    path: String,
    /// Echoed back in the report's "Test parameters:" line, as the original does.
    echo: String,
}

/// Parse frametest's argument grammar.
pub fn parse(argv: &[String]) -> Result<Args> {
    let mut spec = default_spec();
    let mut threads = 1u32;
    let mut streaming = false;
    let mut update_secs = 1.0;
    let mut hist_window = None;
    let mut csv = None;
    let (mut noinfo, mut verbose) = (false, false);
    let mut path = String::new();
    // `-w`/`-z` interact: -z overrides -w's size, so remember what each set.
    let mut size_kb: Option<u64> = None;
    let mut custom_kb: Option<u64> = None;
    let mut echo: Vec<String> = Vec::new();

    let mut i = 0;
    while i < argv.len() {
        let arg = argv[i].clone();

        // Long options are all `--name` or `--name=value`.
        if let Some(long) = arg.strip_prefix("--") {
            let (key, val) = match long.split_once('=') {
                Some((k, v)) => (k, Some(v.to_string())),
                None => (long, None),
            };
            match key {
                "header" => spec.header_kb = need_long(key, val)?.parse()?,
                "name" => spec.name_pattern = need_long(key, val)?,
                "prealloc" => spec.prealloc = need_long(key, val)?.parse()?,
                "noinfo" => noinfo = true,
                "verbose" => verbose = true,
                "buffered" => spec.direct_io = false,
                "help" => {
                    print!("{USAGE}");
                    std::process::exit(0);
                }
                _ => bail!("unknown option --{key}"),
            }
            echo.push(arg);
            i += 1;
            continue;
        }

        if !arg.starts_with('-') || arg == "-" {
            path = arg;
            i += 1;
            continue;
        }

        // Short option. The flag letter is one char; anything after it in the
        // same token is an attached argument (`-n1800`).
        let bytes = arg.as_bytes();
        let flag = bytes[1] as char;
        let attached = if arg.len() > 2 { Some(arg[2..].to_string()) } else { None };
        let attached_raw = attached.clone();

        // Flags that take no argument must not swallow the next token.
        let takes_arg = matches!(
            flag,
            'w' | 'n' | 'a' | 't' | 'f' | 'q' | 'b' | 'l' | 'i' | 'z' | 'd' | 'p' | 'c' | 'u'
                | 'h' | 'x'
        );
        let mut value = attached;
        if takes_arg && value.is_none() {
            i += 1;
            value = argv.get(i).cloned();
        }
        let need = |v: &Option<String>| -> Result<String> {
            v.clone().with_context(|| format!("-{flag} requires an argument"))
        };

        match flag {
            'w' => {
                spec.mode = FrameMode::Write;
                let v = need(&value)?;
                size_kb = Some(parse_size(&v)?);
            }
            'e' => spec.mode = FrameMode::Empty,
            'r' => {
                spec.mode = FrameMode::Read;
                // `-r` takes an *optional* size token: `-r 4k` and a bare `-r`
                // are both documented. Consume the next token only when it
                // actually looks like a size, so `-r /mnt/x` keeps its path.
                if let Some(v) = attached_or_size_peek(&attached_raw, argv, &mut i) {
                    size_kb = Some(parse_size(&v)?);
                }
            }
            's' => streaming = true,
            'n' => spec.frame_count = need(&value)?.parse()?,
            'a' => spec.async_depth = need(&value)?.parse()?,
            't' => threads = need(&value)?.parse::<u32>()?.max(1),
            'o' => spec.out_of_order = true,
            'f' => spec.fps_limit = need(&value)?.parse()?,
            'q' => spec.queue_depth = need(&value)?.parse()?,
            'b' => spec.prebuffer = need(&value)?.parse()?,
            'v' => spec.order = FrameOrder::Reverse,
            'm' => spec.order = FrameOrder::Random,
            'l' => spec.loop_frames = need(&value)?.parse()?,
            'i' => spec.ios_per_frame = need(&value)?.parse::<u32>()?.max(1),
            'z' => custom_kb = Some(need(&value)?.parse()?),
            'd' => spec.files_per_dir = need(&value)?.parse()?,
            'p' => spec.pause_secs = need(&value)?.parse()?,
            'c' => spec.crop_percent = need(&value)?.parse()?,
            'u' => update_secs = need(&value)?.parse()?,
            'h' => hist_window = Some(need(&value)?.parse()?),
            'x' => csv = Some(need(&value)?),
            _ => bail!("unknown option -{flag}"),
        }
        // The original echoes parameters back in attached form with sizes
        // already normalised to KB — `-w 2k` comes back as `-w12512`. Rebuild
        // the token rather than replaying what the user typed.
        match flag {
            'w' => echo.push(format!("-w{}", size_kb.unwrap_or(0))),
            'z' => echo.push(format!("-z{}", custom_kb.unwrap_or(0))),
            'r' if size_kb.is_some() => echo.push(format!("-r{}", size_kb.unwrap())),
            _ => {
                if takes_arg {
                    echo.push(format!("-{flag}{}", value.clone().unwrap_or_default()));
                } else {
                    echo.push(format!("-{flag}"));
                }
            }
        }
        i += 1;
    }

    if path.is_empty() {
        bail!("no path given");
    }

    // -z wins over -w's preset, but -w is still required to pick the mode.
    let total_kb = custom_kb
        .or(size_kb)
        .context("no frame size: pass -w sd|hd|2k|4k|<KB> or -z <KB>")?;
    spec.frame_bytes = if custom_kb.is_some() || size_kb.is_some() {
        total_kb * 1024
    } else {
        0
    };
    // A preset already includes its payload; a KB figure is the whole frame.
    spec.storage = FrameStorage::Disk;
    spec.path = path.clone();

    if matches!(spec.mode, FrameMode::Empty) {
        spec.frame_bytes = spec.frame_bytes.max(spec.header_kb as u64 * 1024);
    }

    Ok(Args {
        spec,
        threads,
        streaming,
        update_secs,
        hist_window,
        csv,
        noinfo,
        verbose,
        path,
        echo: echo.join(" "),
    })
}

fn need_long(key: &str, v: Option<String>) -> Result<String> {
    v.with_context(|| format!("--{key} requires a value"))
}

/// Does this token look like a frame size rather than a path? Used for `-r`,
/// whose size argument is optional — `-r 4k /mnt/x` and `-r /mnt/x` are both
/// valid, so we can only tell them apart by inspecting the token.
fn looks_like_size(tok: &str) -> bool {
    preset_payload_bytes(tok).is_some() || tok.parse::<u64>().is_ok()
}

/// Resolve an optional size argument: attached form wins, otherwise peek at the
/// next token and consume it only if it is a size. Advances `i` when it consumes.
fn attached_or_size_peek(
    attached: &Option<String>,
    argv: &[String],
    i: &mut usize,
) -> Option<String> {
    if let Some(a) = attached {
        return Some(a.clone());
    }
    let next = argv.get(*i + 1)?;
    if looks_like_size(next) {
        *i += 1;
        return Some(next.clone());
    }
    None
}

/// `-w` accepts a preset name or a KB figure. Presets resolve to their payload
/// plus the default header, expressed in KB so the echo line matches the
/// original (`-w 2k` echoes as `-w12512`).
fn parse_size(v: &str) -> Result<u64> {
    if let Some(payload) = preset_payload_bytes(v) {
        return Ok((payload + 64 * 1024) / 1024);
    }
    v.parse::<u64>()
        .with_context(|| format!("bad frame size '{v}': want sd|hd|2k|4k or a KB number"))
}

fn default_spec() -> FrameSpec {
    FrameSpec {
        mode: FrameMode::Read, // frametest's default
        frame_bytes: 0,
        frame_count: 1800,
        fps_limit: 0.0,
        queue_depth: 0,
        prebuffer: 5,
        order: FrameOrder::Sequential,
        storage: FrameStorage::Disk,
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

/// Entry point for `bwagent frametest`.
pub fn run(argv: &[String]) -> Result<()> {
    // The original prints help when run with no arguments (it does not use -h
    // for help — that is the histogram window).
    if argv.is_empty() {
        print!("{USAGE}");
        return Ok(());
    }
    let args = parse(argv)?;

    if !args.noinfo {
        println!(
            "bwagent frametest {} — frametest-compatible local storage test",
            env!("CARGO_PKG_VERSION")
        );
        println!();
    }
    if args.streaming {
        // -s is the single-file streaming test. In the console this is exactly
        // the "Large file" transfer mode; locally it is a straight sequential
        // read or write of one file, with no per-frame open/close.
        return run_streaming(&args);
    }

    let plan = Arc::new(FramePlan::new(args.spec.clone())?);
    if matches!(args.spec.mode, FrameMode::Read) {
        if !std::path::Path::new(&args.path).is_dir() {
            bail!("read test needs an existing directory of frames: {}", args.path);
        }
    } else {
        std::fs::create_dir_all(&args.path)
            .with_context(|| format!("create {}", args.path))?;
    }
    frame::apply_pause(&args.spec);

    let counters = FrameCounters::new();
    let queue = FrameQueue::new(args.spec.queue_depth as usize);
    let stop: Arc<AtomicBool> = Arc::new(AtomicBool::new(false));

    let start = Instant::now();
    let pacer = {
        let (p, q, c, s) = (plan.clone(), queue.clone(), counters.clone(), stop.clone());
        std::thread::spawn(move || frame::run_pacer(p, q, c, s))
    };

    let mut workers = Vec::new();
    for _ in 0..args.threads {
        let (p, q, c, s) = (plan.clone(), queue.clone(), counters.clone(), stop.clone());
        let verbose = args.verbose;
        workers.push(std::thread::spawn(move || local_worker(p, q, c, s, verbose)));
    }

    // Sampler: drives the trailing-window table and the live display.
    let sampler = {
        let (c, s) = (counters.clone(), stop.clone());
        let interval = args.update_secs.max(0.05);
        let quiet = args.noinfo;
        std::thread::spawn(move || {
            let t0 = Instant::now();
            while !s.load(Ordering::Relaxed) {
                std::thread::sleep(std::time::Duration::from_secs_f64(interval));
                let p = c.tick(t0.elapsed().as_secs_f64());
                if !quiet {
                    // Single updating line, like the original's live display.
                    eprint!(
                        "\r  {} frames  {:.1} fps  {:.2} ms/frame  {} dropped   ",
                        p.frames_done, p.fps, p.frame_ms_avg, p.frames_dropped
                    );
                }
            }
            if !quiet {
                eprintln!();
            }
        })
    };

    let _ = pacer.join();
    for w in workers {
        let _ = w.join();
    }
    stop.store(true, Ordering::Relaxed);
    let _ = sampler.join();

    let elapsed = start.elapsed().as_secs_f64();
    let stats = counters.finish(elapsed);
    print_report(&args, &stats, elapsed);
    if let Some(path) = &args.csv {
        write_csv(path, &args, &stats, elapsed)?;
        if !args.noinfo {
            println!("\nDetailed timing written to {path}");
        }
    }
    Ok(())
}

/// Local worker: same frame work as the network source, minus the wire.
fn local_worker(
    plan: Arc<FramePlan>,
    queue: Arc<FrameQueue>,
    counters: Arc<FrameCounters>,
    stop: Arc<AtomicBool>,
    verbose: bool,
) {
    let mut buf = AlignedBuf::new(plan.spec.frame_bytes.max(1) as usize);
    while !stop.load(Ordering::Relaxed) {
        let Some(pos) = queue.take() else { break };
        let idx = plan.at(pos);
        let whole = Instant::now();
        let mut t = FrameTiming::default();
        match frame::load_frame(&plan, idx, &mut buf, &mut t) {
            Ok(n) => {
                t.total_ns = whole.elapsed().as_nanos() as u64;
                t.bytes = n as u64;
                counters.record_frame(&t);
            }
            Err(e) => {
                if verbose {
                    eprintln!("frame {idx}: {e:#}");
                }
                counters.record_drop();
            }
        }
    }
}

/// `-s`: one file, read or written straight through. No per-frame open/close, so
/// the report's create/close rows are zero by construction — which is the point
/// of the mode.
fn run_streaming(args: &Args) -> Result<()> {
    use std::io::{Read, Write};
    let spec = &args.spec;
    let chunk = spec.frame_bytes.max(4096) as usize;
    let count = spec.frame_count;
    let counters = FrameCounters::new();
    frame::apply_pause(spec);

    let start = Instant::now();
    let mut buf = AlignedBuf::new(chunk);
    let write = !matches!(spec.mode, FrameMode::Read);

    let t_open = Instant::now();
    let mut f = frame::open_frame(std::path::Path::new(&args.path), write, spec.direct_io)?;
    let open_ns = t_open.elapsed().as_nanos() as u64;

    if write && spec.prealloc > 0 {
        // Best-effort preallocation; not every filesystem supports it, and a
        // failure here should not fail the test.
        let _ = f.set_len(spec.prealloc * chunk as u64);
    }

    for _ in 0..count {
        let t0 = Instant::now();
        let n = if write {
            f.write_all(buf.as_slice()).map(|()| chunk)
        } else {
            f.read(buf.as_mut_slice())
        };
        let io_ns = t0.elapsed().as_nanos() as u64;
        match n {
            Ok(0) => break,
            Ok(n) => counters.record_frame(&FrameTiming {
                create_ns: 0,
                io_ns,
                close_ns: 0,
                total_ns: io_ns,
                bytes: n as u64,
            }),
            Err(e) => return Err(e).context("streaming I/O"),
        }
        counters.tick(start.elapsed().as_secs_f64());
    }
    let t_close = Instant::now();
    drop(f);
    let close_ns = t_close.elapsed().as_nanos() as u64;

    let elapsed = start.elapsed().as_secs_f64();
    let mut stats = counters.finish(elapsed);
    // Attribute the single open/close to the run rather than to any one frame.
    stats.create.max_ms = open_ns as f64 / 1.0e6;
    stats.close.max_ms = close_ns as f64 / 1.0e6;
    print_report(args, &stats, elapsed);
    Ok(())
}

// ---------------------------------------------------------------------------
// Report rendering — matches the original's layout
// ---------------------------------------------------------------------------

fn print_report(args: &Args, s: &FrameStats, elapsed: f64) {
    let frame_bytes = args.spec.frame_bytes;
    println!("Test parameters:      {}", args.echo);
    println!("Test duration:        {:.0} secs", elapsed);
    println!(
        "Frames transferred:   {} ({:.3} MB)",
        s.frames_transferred,
        s.bytes_total as f64 / (1024.0 * 1024.0)
    );
    if s.frames_dropped > 0 {
        println!("Frames dropped:       {}", s.frames_dropped);
    }
    println!(
        "Fastest frame:        {:.3} ms ({:.2} MB/s)",
        s.fastest_ms,
        s.fastest_mb_per_sec(frame_bytes)
    );
    println!(
        "Slowest frame:        {:.3} ms ({:.2} MB/s)",
        s.slowest_ms,
        s.slowest_mb_per_sec(frame_bytes)
    );
    println!();

    println!("Averaged details:");
    println!("             Open        I/O         Frame      Data rate   Frame rate");
    for w in &s.windows {
        let label = if w.label == "Overall" {
            " Overall:".to_string()
        } else if w.label == "1s" {
            " Last 1s:".to_string()
        } else {
            format!("{:>8}:", w.label)
        };
        println!(
            "{label}  {:>6.3} ms  {:>8.2} ms  {:>8.2} ms  {:>8.2} MB/s  {:>5.1} fps",
            w.open_ms, w.io_ms, w.frame_ms, w.mb_per_sec, w.fps
        );
    }
    println!();

    print_histogram(s, args.hist_window);

    let mb_s = if elapsed > 0.0 {
        s.bytes_total as f64 / elapsed
    } else {
        0.0
    };
    println!(
        "\nOverall frame rate .... {:.2} fps ({:.0} bytes/s)\n",
        if elapsed > 0.0 {
            s.frames_transferred as f64 / elapsed
        } else {
            0.0
        },
        mb_s
    );
    stage_block("file", &s.file);
    stage_block("create", &s.create);
    stage_block(if matches!(args.spec.mode, FrameMode::Read) { "read" } else { "write" }, &s.io);
    stage_block("close", &s.close);
}

fn stage_block(name: &str, m: &crate::protocol::MinAvgMax) {
    for (kind, v) in [
        ("Average", m.avg_ms),
        ("Shortest", m.min_ms),
        ("Longest", m.max_ms),
    ] {
        // The original dot-pads every label to the same column so the numbers
        // line up regardless of label length.
        let label = format!("{kind} {name} time ");
        println!(" {:.<width$} {:.3} ms", label, v, width = LABEL_COL);
    }
    println!();
}

/// Column the dot leaders pad out to, matching the reference report.
const LABEL_COL: usize = 24;

/// The 13-bucket ASCII histogram, with the same axis as the original.
fn print_histogram(s: &FrameStats, window: Option<f64>) {
    println!("Histogram of frame completion times:");
    if let Some(w) = window {
        println!("  (last {w} seconds)");
    }
    let total: u64 = s.histogram.iter().sum();
    if total == 0 {
        println!("  (no frames completed)");
        return;
    }
    const ROWS: usize = 10;
    let max = *s.histogram.iter().max().unwrap_or(&1) as f64;

    // Every line below shares this prefix width, so bars, the axis rule and the
    // tick labels all land in the same columns.
    const PREFIX: usize = 5;
    const CELL: usize = 5;

    for row in 0..ROWS {
        // Top row is 100%; each row down is one tenth of the peak bucket.
        let threshold = (ROWS - row) as f64 / ROWS as f64;
        let label = if row == 0 { "100%" } else { "" };
        let mut line = String::new();
        for count in &s.histogram {
            let h = if max > 0.0 { *count as f64 / max } else { 0.0 };
            line.push_str(if h >= threshold { "  *  " } else { "     " });
        }
        println!("{label:>width$}|{line}", width = PREFIX);
    }
    println!(
        "{:>width$}+{}",
        "",
        "-".repeat(CELL * HISTOGRAM_LABELS.len()),
        width = PREFIX
    );
    print!("{:>width$} ", "ms", width = PREFIX);
    for l in HISTOGRAM_LABELS {
        // Tick labels centre under their bucket's bar column.
        print!("{l:^width$}", width = CELL);
    }
    println!();
}

/// `-x`: comma-separated detail export. The original's exact column set is not
/// documented anywhere we could find, so this is our own schema — self-describing
/// via a header row rather than pretending to match a layout we cannot verify.
fn write_csv(path: &str, args: &Args, s: &FrameStats, elapsed: f64) -> Result<()> {
    use std::io::Write;
    let path = if path.ends_with(".csv") {
        path.to_string()
    } else {
        format!("{path}.csv")
    };
    let mut f = std::fs::File::create(&path).with_context(|| format!("create {path}"))?;

    writeln!(f, "# bwagent frametest detail export")
        .and_then(|()| writeln!(f, "# parameters,{}", args.echo))?;
    writeln!(
        f,
        "section,label,frames,dropped,bytes,seconds,fps,mb_per_sec,\
         open_ms,io_ms,frame_ms,min_ms,max_ms"
    )?;
    writeln!(
        f,
        "summary,overall,{},{},{},{:.6},{:.3},{:.3},{:.6},{:.6},{:.6},{:.6},{:.6}",
        s.frames_transferred,
        s.frames_dropped,
        s.bytes_total,
        elapsed,
        if elapsed > 0.0 { s.frames_transferred as f64 / elapsed } else { 0.0 },
        if elapsed > 0.0 { s.bytes_total as f64 / (1024.0 * 1024.0) / elapsed } else { 0.0 },
        s.create.avg_ms,
        s.io.avg_ms,
        s.file.avg_ms,
        s.fastest_ms,
        s.slowest_ms
    )?;
    for w in &s.windows {
        writeln!(
            f,
            "window,{},,,,,{:.3},{:.3},{:.6},{:.6},{:.6},,",
            w.label, w.fps, w.mb_per_sec, w.open_ms, w.io_ms, w.frame_ms
        )?;
    }
    for (i, count) in s.histogram.iter().enumerate() {
        writeln!(f, "histogram,{},{},,,,,,,,,,", HISTOGRAM_LABELS[i], count)?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn args(s: &str) -> Args {
        let v: Vec<String> = s.split_whitespace().map(String::from).collect();
        parse(&v).expect("parse")
    }

    #[test]
    fn separated_and_attached_forms_agree() {
        let a = args("-w 2k -n 1800 -t 4 /tmp/x");
        let b = args("-w2k -n1800 -t4 /tmp/x");
        assert_eq!(a.spec.frame_count, b.spec.frame_count);
        assert_eq!(a.spec.frame_bytes, b.spec.frame_bytes);
        assert_eq!(a.threads, b.threads);
        assert_eq!(a.path, b.path);
    }

    /// `-w 2k` must resolve to the size the reference output echoes: 12512 KB.
    #[test]
    fn preset_resolves_to_the_documented_size() {
        let a = args("-w 2k -n 1800 /tmp/x");
        assert_eq!(a.spec.frame_bytes, 12_512 * 1024);
        assert_eq!(a.spec.frame_bytes, 12_812_288);

        let b = args("-w 4k -n 10 /tmp/x");
        assert_eq!(b.spec.frame_bytes, 49_856 * 1024);
        assert_eq!(b.spec.frame_bytes, 51_052_544);
    }

    /// The flags most likely to be implemented wrong. Each assertion here is a
    /// documented behaviour of the original.
    #[test]
    fn easily_confused_flags_have_the_documented_meaning() {
        // -v is reverse order, not verbose.
        let a = args("-w 2k -v /tmp/x");
        assert!(matches!(a.spec.order, FrameOrder::Reverse));
        assert!(!a.verbose);

        // --verbose is verbose.
        assert!(args("-w 2k --verbose /tmp/x").verbose);

        // -o is out-of-order completion, not an output file.
        assert!(args("-w 2k -o /tmp/x").spec.out_of_order);

        // -x is the CSV export.
        assert_eq!(args("-w 2k -x out.csv /tmp/x").csv.as_deref(), Some("out.csv"));

        // -c is crop percent, not CSV.
        assert_eq!(args("-r -z 100 -c 50 /tmp/x").spec.crop_percent, 50);

        // -h is the histogram window, not help.
        assert_eq!(args("-w 2k -h 30 /tmp/x").hist_window, Some(30.0));

        // -m is random order.
        assert!(matches!(args("-w 2k -m /tmp/x").spec.order, FrameOrder::Random));
    }

    /// The documented `-w 1 -z 40000` idiom: -w picks write mode, -z sets size.
    #[test]
    fn z_overrides_w_size() {
        let a = args("-w 1 -z 40000 -n 3000 -t 4 /tmp/x");
        assert!(matches!(a.spec.mode, FrameMode::Write));
        assert_eq!(a.spec.frame_bytes, 40_000 * 1024);
    }

    #[test]
    fn defaults_match_frametest() {
        let a = args("-w 2k /tmp/x");
        assert_eq!(a.spec.frame_count, 1800); // -n
        assert_eq!(a.spec.prebuffer, 5); // -b
        assert_eq!(a.spec.header_kb, 64); // --header
        assert_eq!(a.spec.ios_per_frame, 1); // -i
        assert_eq!(a.spec.loop_frames, 0); // -l
        assert_eq!(a.threads, 1);
        assert_eq!(a.update_secs, 1.0); // -u
        assert!(a.spec.direct_io, "direct I/O is the default");
    }

    /// Read is frametest's default mode when neither -w nor -e is given.
    #[test]
    fn read_is_the_default_mode() {
        let a = args("-r 2k -n 100 /tmp/x");
        assert!(matches!(a.spec.mode, FrameMode::Read));
        let b = args("-z 100 /tmp/x");
        assert!(matches!(b.spec.mode, FrameMode::Read));
    }

    #[test]
    fn empty_mode_is_selected_by_e() {
        let a = args("-e -z 4 -n 3000 -t 4 /tmp/x");
        assert!(matches!(a.spec.mode, FrameMode::Empty));
    }

    #[test]
    fn long_options_parse() {
        let a = args("-w 2k --header=32 --name=shot-172.000001.exr --noinfo /tmp/x");
        assert_eq!(a.spec.header_kb, 32);
        assert_eq!(a.spec.name_pattern, "shot-172.000001.exr");
        assert!(a.noinfo);
        assert!(args("-w 2k --buffered /tmp/x").spec.direct_io == false);
    }

    /// Real invocations found in vendor documentation must all parse.
    #[test]
    fn documented_real_world_invocations_parse() {
        for line in [
            "-w 4k -n 3000 -t 1 /mnt/server-xyz/TEST_1",
            "-w 4k -n 3000 -q 10 -b 5 -f 24 -t 4 /mnt/server-xyz/TEST_2",
            "-w 1 -z 40000 -n 3000 -t 4 /mnt/server-xyz/TEST_3",
            "-e -n 3000 -t 4 -z 4 /mnt/server-xyz/TEST_4",
            "-r 4k -n 3000 -q 10 -b 5 -f 24 -t 4 /mnt/server-xyz/TEST_1",
            "-r 4k -n 3000 -t 4 --name=shot-172.000001.exr /mnt/server-xyz/DPX-12",
            "-r 4k -n 3000 -t 4 -x read-out.csv TEST_2",
            "-w 4k -n 10000 -t 4 -f 64 /mmfs1/NLSAS/Paul/",
        ] {
            let v: Vec<String> = line.split_whitespace().map(String::from).collect();
            parse(&v).unwrap_or_else(|e| panic!("failed to parse `{line}`: {e:#}"));
        }
    }

    #[test]
    fn queue_and_pacing_flags_are_captured() {
        let a = args("-w 4k -n 3000 -q 10 -b 5 -f 24 -t 4 /mnt/x");
        assert_eq!(a.spec.queue_depth, 10);
        assert_eq!(a.spec.prebuffer, 5);
        assert_eq!(a.spec.fps_limit, 24.0);
        assert_eq!(a.threads, 4);
    }

    #[test]
    fn missing_path_or_size_is_an_error() {
        let no_path: Vec<String> = "-w 2k -n 10".split_whitespace().map(String::from).collect();
        assert!(parse(&no_path).is_err());
        let no_size: Vec<String> = "-t 4 /tmp/x".split_whitespace().map(String::from).collect();
        assert!(parse(&no_size).is_err());
    }

    #[test]
    fn unknown_flags_are_rejected_not_ignored() {
        let bad: Vec<String> = "-w 2k -Q 9 /tmp/x".split_whitespace().map(String::from).collect();
        assert!(parse(&bad).is_err());
        let bad2: Vec<String> = "-w 2k --nope /tmp/x".split_whitespace().map(String::from).collect();
        assert!(parse(&bad2).is_err());
    }

    /// The parameter echo drives the report's first line; attached forms must
    /// round-trip so the report reads like the original's.
    #[test]
    fn parameter_echo_uses_attached_form() {
        let a = args("-w 2k -n 1800 /tmp/x");
        assert!(a.echo.contains("-w12512"), "echo was: {}", a.echo);
        assert!(a.echo.contains("-n1800"), "echo was: {}", a.echo);
    }
}

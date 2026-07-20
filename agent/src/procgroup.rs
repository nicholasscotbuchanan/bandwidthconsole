//! Multi-process sender fan-out.
//!
//! `processes = N` means N *real* OS processes generating load, not just more
//! threads. The lead process spawns N children in `worker` mode, each running one
//! share of the scenario and streaming its per-second telemetry as NDJSON on
//! stdout. The lead merges those streams into the single run-level telemetry the
//! console sees, and sums the child summaries into one aggregate result.
//!
//! With `processes = 1` there is nothing to fan out, so we run the engine
//! in-process and skip the child machinery entirely.

use std::collections::HashMap;
use std::process::Stdio;
use std::sync::atomic::Ordering;

use anyhow::{Context, Result};
use tokio::io::{AsyncBufReadExt, BufReader};
use tokio::process::Command;

use crate::engine::{self, Stop, Tx};
use crate::protocol::{
    AgentMsg, Lane, LaneUpdate, LatencyPhases, RunSummary, Scenario, TelemetrySample,
};

/// Run the sender, fanning out to child processes when `scenario.processes > 1`.
pub async fn run_send(
    run_id: String,
    scenario: Scenario,
    target_addr: String,
    tx: Tx,
    stop: Stop,
) -> Result<RunSummary> {
    if scenario.processes <= 1 {
        return engine::run_send(run_id, scenario, target_addr, tx, stop).await;
    }

    let n = scenario.processes;
    let exe = std::env::current_exe().context("current_exe")?;
    let mut child_sc = scenario.clone();
    child_sc.processes = 1;
    // Split an offered-rate target evenly across processes.
    if child_sc.target_mbps > 0 {
        child_sc.target_mbps = (child_sc.target_mbps / n).max(1);
    }
    // Split the frame budget and the fps deadline the same way, so N processes
    // move the same total frames at the same total rate as one process would —
    // otherwise `processes` would silently multiply the workload.
    if let Some(f) = child_sc.frame.as_mut() {
        f.frame_count = (f.frame_count / n as u64).max(1);
        if f.fps_limit > 0.0 {
            f.fps_limit /= n as f64;
        }
    }
    // Frames the whole group is expected to retire. Derived from what the
    // children were actually told to move, not from the original count: the
    // division above truncates, so 41 frames across 2 processes really is 40,
    // and testing against 41 would wait forever for a frame nobody will send.
    let frame_target = child_sc
        .frame
        .as_ref()
        .filter(|_| scenario.is_multi_file())
        .map(|f| f.total_frames() * n as u64)
        .unwrap_or(0);
    let sc_json = serde_json::to_string(&child_sc)?;

    // Latest per-child sample (index -> (mbps, pps, rtt_ms, retransmits)).
    let latest: std::sync::Arc<tokio::sync::Mutex<HashMap<u32, TelemetrySample>>> =
        std::sync::Arc::new(tokio::sync::Mutex::new(HashMap::new()));
    let summaries: std::sync::Arc<tokio::sync::Mutex<Vec<RunSummary>>> =
        std::sync::Arc::new(tokio::sync::Mutex::new(Vec::new()));
    // Latest lifecycle lane per (child, lane). Each child stages and moves its
    // own share of the frames, so the group's progress is only visible once
    // these are merged — without this the console sees no staging at all on a
    // multi-process run, which is exactly the case that takes longest.
    let latest_lanes: std::sync::Arc<tokio::sync::Mutex<HashMap<(u32, Lane), LaneUpdate>>> =
        std::sync::Arc::new(tokio::sync::Mutex::new(HashMap::new()));

    let mut readers = Vec::new();
    for idx in 0..n {
        let mut cmd = Command::new(&exe);
        cmd.arg("worker")
            .arg("--run-id")
            .arg(&run_id)
            .arg("--target")
            .arg(&target_addr)
            .arg("--scenario")
            .arg(&sc_json)
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .kill_on_drop(true);
        let mut child = cmd.spawn().context("spawn worker process")?;
        let stdout = child.stdout.take().unwrap();
        let latest = latest.clone();
        let summaries = summaries.clone();
        let latest_lanes = latest_lanes.clone();
        readers.push(tokio::spawn(async move {
            let mut lines = BufReader::new(stdout).lines();
            while let Ok(Some(line)) = lines.next_line().await {
                if let Ok(msg) = serde_json::from_str::<AgentMsg>(&line) {
                    match msg {
                        AgentMsg::Telemetry(s) => {
                            latest.lock().await.insert(idx, s);
                        }
                        AgentMsg::RunComplete { summary } => {
                            summaries.lock().await.push(summary);
                        }
                        AgentMsg::Phase(u) => {
                            latest_lanes.lock().await.insert((idx, u.lane), u);
                        }
                        _ => {}
                    }
                }
            }
            let _ = child.wait().await;
        }));
    }

    // Lead's own 1 Hz ticker: emit the summed telemetry the console records.
    let start = std::time::Instant::now();
    let mut ticker = tokio::time::interval(std::time::Duration::from_millis(1000));
    ticker.tick().await;
    loop {
        ticker.tick().await;
        let t = start.elapsed().as_secs_f64();
        let snap: Vec<TelemetrySample> = latest.lock().await.values().cloned().collect();
        let mbps: f64 = snap.iter().map(|s| s.mbps).sum();
        let pps: f64 = snap.iter().map(|s| s.pps).sum();
        let rtt = snap.iter().map(|s| s.rtt_ms).fold(0.0, f64::max);
        let retr: u64 = snap.iter().map(|s| s.retransmits).sum();
        let cpu: f64 = snap.iter().map(|s| s.cpu_percent).sum();
        let per_stream: Vec<f64> = snap.iter().flat_map(|s| s.per_stream.clone()).collect();
        // Relay the group's merged lanes, so staging and transfer progress show
        // up on a multi-process run the same way they do on a single one.
        {
            let lanes: Vec<LaneUpdate> = latest_lanes.lock().await.values().cloned().collect();
            for u in merge_lanes(&lanes, &run_id) {
                let _ = tx.send(AgentMsg::Phase(u));
            }
        }

        let frame = merge_progress(&snap);
        let framed = frame.is_some();
        let frames_left = frame.as_ref().is_some_and(|f: &crate::protocol::FrameProgress| {
            f.frames_done + f.frames_dropped < frame_target
        });
        let _ = tx.send(AgentMsg::Telemetry(TelemetrySample {
            run_id: run_id.clone(),
            end: "send".to_string(),
            t_secs: t,
            mbps,
            pps,
            rtt_ms: rtt,
            retransmits: retr,
            cpu_percent: cpu,
            per_stream,
            frame,
        }));
        // Frame runs end on their frame budget; everything else on the clock.
        let done = if framed && frame_target > 0 {
            !frames_left
        } else {
            t >= scenario.duration_secs as f64 + 1.5
        };
        if stop.load(Ordering::Relaxed) || done {
            break;
        }
    }
    stop.store(true, Ordering::Relaxed);

    // Give children a moment to flush their final summaries.
    let _ = tokio::time::timeout(std::time::Duration::from_secs(3), async {
        for r in readers {
            let _ = r.await;
        }
    })
    .await;

    let sums = summaries.lock().await.clone();
    Ok(aggregate(&run_id, &sums))
}

/// Sum the children's live frame progress into one group-level view. Counts add;
/// per-frame times are averaged over the children actually reporting them, since
/// each child measures the same work independently.
fn merge_progress(snap: &[TelemetrySample]) -> Option<crate::protocol::FrameProgress> {
    let frames: Vec<&crate::protocol::FrameProgress> =
        snap.iter().filter_map(|s| s.frame.as_ref()).collect();
    if frames.is_empty() {
        return None;
    }
    let n = frames.len() as f64;
    let mean = |f: fn(&crate::protocol::FrameProgress) -> f64| -> f64 {
        frames.iter().map(|p| f(p)).sum::<f64>() / n
    };
    Some(crate::protocol::FrameProgress {
        fps: frames.iter().map(|p| p.fps).sum(),
        frames_done: frames.iter().map(|p| p.frames_done).sum(),
        frames_dropped: frames.iter().map(|p| p.frames_dropped).sum(),
        frame_ms_avg: mean(|p| p.frame_ms_avg),
        open_ms_avg: mean(|p| p.open_ms_avg),
        io_ms_avg: mean(|p| p.io_ms_avg),
        close_ms_avg: mean(|p| p.close_ms_avg),
    })
}

/// Merge child frame reports. Counts and histograms add; extremes take the true
/// extreme across children; means are weighted by each child's frame count so a
/// child that moved more frames pulls the average proportionally.
fn merge_frame_stats(sums: &[RunSummary]) -> Option<crate::protocol::FrameStats> {
    use crate::protocol::{FrameStats, MinAvgMax};
    let stats: Vec<&FrameStats> = sums.iter().filter_map(|s| s.frame.as_ref()).collect();
    if stats.is_empty() {
        return None;
    }
    let total: u64 = stats.iter().map(|s| s.frames_transferred).sum();
    let stage = |pick: fn(&FrameStats) -> &MinAvgMax| MinAvgMax {
        min_ms: stats
            .iter()
            .map(|s| pick(s).min_ms)
            .filter(|v| *v > 0.0)
            .fold(f64::INFINITY, f64::min),
        // Weighted by each child's frame count, so a child that moved more
        // frames pulls the average proportionally. Guards the all-dropped case.
        avg_ms: if total == 0 {
            0.0
        } else {
            stats
                .iter()
                .map(|s| pick(s).avg_ms * s.frames_transferred as f64)
                .sum::<f64>()
                / total as f64
        },
        max_ms: stats.iter().map(|s| pick(s).max_ms).fold(0.0, f64::max),
    };
    let finite = |m: MinAvgMax| MinAvgMax {
        min_ms: if m.min_ms.is_finite() { m.min_ms } else { 0.0 },
        ..m
    };

    let mut histogram = vec![0u64; 13];
    for s in &stats {
        for (i, v) in s.histogram.iter().enumerate() {
            if i < histogram.len() {
                histogram[i] += v;
            }
        }
    }
    let file = finite(stage(|s| &s.file));
    Some(FrameStats {
        frames_transferred: total,
        frames_dropped: stats.iter().map(|s| s.frames_dropped).sum(),
        bytes_total: stats.iter().map(|s| s.bytes_total).sum(),
        fastest_ms: file.min_ms,
        slowest_ms: file.max_ms,
        file,
        create: finite(stage(|s| &s.create)),
        io: finite(stage(|s| &s.io)),
        close: finite(stage(|s| &s.close)),
        histogram,
        // Windows are wall-clock views of the same run; the lead's own sampler
        // is the group-level one, so take the first child's shape and let the
        // rates add up across children.
        windows: stats[0]
            .windows
            .iter()
            .enumerate()
            .map(|(i, w)| crate::protocol::WindowRow {
                label: w.label.clone(),
                open_ms: w.open_ms,
                io_ms: w.io_ms,
                frame_ms: w.frame_ms,
                mb_per_sec: stats
                    .iter()
                    .filter_map(|s| s.windows.get(i))
                    .map(|x| x.mb_per_sec)
                    .sum(),
                fps: stats
                    .iter()
                    .filter_map(|s| s.windows.get(i))
                    .map(|x| x.fps)
                    .sum(),
            })
            .collect(),
    })
}

/// Collapse per-child lanes into one lane per stage for the whole group.
///
/// Counts add up because each child stages and moves a disjoint share of the
/// frames. The extent is the union — earliest start to latest end — since the
/// group is busy in a stage for as long as any child is. `busy_ms` is summed
/// across children, so dividing it by the summed frame count still gives the
/// mean cost of one frame, which is the number the Gantt reports.
///
/// Child processes start within milliseconds of each other but each keeps its
/// own epoch, so the union's edges carry that much jitter. It is immaterial
/// against stages measured in seconds.
fn merge_lanes(snap: &[LaneUpdate], run_id: &str) -> Vec<LaneUpdate> {
    let mut out: Vec<LaneUpdate> = Vec::new();
    for lane in [
        Lane::Generate,
        Lane::Read,
        Lane::Transmit,
        Lane::Receive,
        Lane::Write,
    ] {
        let parts: Vec<&LaneUpdate> = snap.iter().filter(|u| u.lane == lane).collect();
        if parts.is_empty() {
            continue;
        }
        out.push(LaneUpdate {
            run_id: run_id.to_string(),
            end: parts[0].end.clone(),
            lane,
            start_ms: parts.iter().map(|u| u.start_ms).fold(f64::MAX, f64::min),
            end_ms: parts.iter().map(|u| u.end_ms).fold(0.0, f64::max),
            busy_ms: parts.iter().map(|u| u.busy_ms).sum(),
            done: parts.iter().map(|u| u.done).sum(),
            total: parts.iter().map(|u| u.total).sum(),
            // Only finished when every child has finished it.
            complete: parts.iter().all(|u| u.complete),
        });
    }
    out
}

/// Merge child summaries into one run-level result.
fn aggregate(run_id: &str, sums: &[RunSummary]) -> RunSummary {
    if sums.is_empty() {
        return RunSummary {
            run_id: run_id.to_string(),
            avg_mbps: 0.0,
            peak_mbps: 0.0,
            bytes_total: 0,
            p50_rtt_ms: 0.0,
            p95_rtt_ms: 0.0,
            p99_rtt_ms: 0.0,
            retransmits: 0,
            sack_active: false,
            phases: LatencyPhases::default(),
            frame: None,
            lanes: Vec::new(),
        };
    }
    let n = sums.len() as f64;
    RunSummary {
        run_id: run_id.to_string(),
        avg_mbps: sums.iter().map(|s| s.avg_mbps).sum(),
        peak_mbps: sums.iter().map(|s| s.peak_mbps).sum(),
        bytes_total: sums.iter().map(|s| s.bytes_total).sum(),
        // Latency aggregates: median of medians, worst-case tails.
        p50_rtt_ms: sums.iter().map(|s| s.p50_rtt_ms).sum::<f64>() / n,
        p95_rtt_ms: sums.iter().map(|s| s.p95_rtt_ms).fold(0.0, f64::max),
        p99_rtt_ms: sums.iter().map(|s| s.p99_rtt_ms).fold(0.0, f64::max),
        retransmits: sums.iter().map(|s| s.retransmits).sum(),
        sack_active: sums.iter().any(|s| s.sack_active),
        // Phases are near-identical across peers; take the first worker's.
        phases: sums[0].phases.clone(),
        frame: merge_frame_stats(sums),
        // Unlike phases, lanes cannot be taken from one worker: each child moved
        // its own share of the frames, so a single child's counts would under-
        // report the group by a factor of N.
        lanes: merge_lanes(
            &sums.iter().flat_map(|s| s.lanes.clone()).collect::<Vec<_>>(),
            run_id,
        ),
    }
}

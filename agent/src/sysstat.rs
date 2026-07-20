//! Process CPU sampling for the sender agent, feeding the console's CPU% chart.
//!
//! Sampled on its own thread and published to an atomic, so the telemetry loop
//! just reads the latest value.
//!
//! On Linux we read `/proc/self/stat` directly rather than going through
//! sysinfo. sysinfo's Linux process-CPU path kept reporting a flat 0% inside
//! containers (it derives the value from a delta against total system CPU time,
//! which did not behave here), while the kernel counters are unambiguous:
//! utime+stime in clock ticks over wall-clock elapsed. macOS keeps sysinfo,
//! where it works correctly.

use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Once;
// Only the Linux sampler needs wall-clock deltas; gate the import so the
// macOS build (which uses sysinfo's own timing) stays warning-free.
#[cfg(target_os = "linux")]
use std::time::Instant;

/// Latest process CPU utilisation, percent × 100 (100.00% == one core busy).
static CPU_PCT_X100: AtomicU64 = AtomicU64::new(0);
static START: Once = Once::new();

const INTERVAL_MS: u64 = 500;

/// Start the background sampler (idempotent).
pub fn start() {
    START.call_once(|| {
        std::thread::Builder::new()
            .name("cpu-sampler".into())
            .spawn(sampler_loop)
            .expect("spawn cpu-sampler");
    });
}

/// Most recent process CPU utilisation (100 == one core fully busy; a
/// multi-threaded sender can exceed 100).
pub fn current() -> f64 {
    CPU_PCT_X100.load(Ordering::Relaxed) as f64 / 100.0
}

fn publish(pct: f64) {
    CPU_PCT_X100.store((pct.max(0.0) * 100.0) as u64, Ordering::Relaxed);
}

// ------------------------------------------------------------------ Linux

#[cfg(target_os = "linux")]
fn sampler_loop() {
    // USER_HZ is 100 on effectively every Linux build; /proc/self/stat reports
    // CPU time in those ticks.
    const USER_HZ: f64 = 100.0;
    let mut last_ticks = read_self_cpu_ticks().unwrap_or(0.0);
    let mut last_at = Instant::now();
    loop {
        std::thread::sleep(std::time::Duration::from_millis(INTERVAL_MS));
        let Some(ticks) = read_self_cpu_ticks() else {
            continue;
        };
        let now = Instant::now();
        let wall = now.duration_since(last_at).as_secs_f64().max(1e-6);
        let cpu_secs = (ticks - last_ticks).max(0.0) / USER_HZ;
        publish(cpu_secs / wall * 100.0);
        last_ticks = ticks;
        last_at = now;
    }
}

/// utime + stime for this process, in clock ticks.
#[cfg(target_os = "linux")]
fn read_self_cpu_ticks() -> Option<f64> {
    let stat = std::fs::read_to_string("/proc/self/stat").ok()?;
    // The comm field is parenthesised and may contain spaces, so fields are
    // counted from after the final ')': state is [0], utime [11], stime [12].
    let rest = &stat[stat.rfind(')')? + 1..];
    let f: Vec<&str> = rest.split_whitespace().collect();
    let utime: f64 = f.get(11)?.parse().ok()?;
    let stime: f64 = f.get(12)?.parse().ok()?;
    Some(utime + stime)
}

// ------------------------------------------------------------- non-Linux

#[cfg(not(target_os = "linux"))]
fn sampler_loop() {
    use sysinfo::{Pid, ProcessRefreshKind, ProcessesToUpdate, System};
    let pid = Pid::from_u32(std::process::id());
    let mut sys = System::new();
    let kind = ProcessRefreshKind::new().with_cpu();
    sys.refresh_processes_specifics(ProcessesToUpdate::Some(&[pid]), true, kind);
    loop {
        std::thread::sleep(std::time::Duration::from_millis(INTERVAL_MS));
        sys.refresh_processes_specifics(ProcessesToUpdate::Some(&[pid]), true, kind);
        let pct = sys.process(pid).map(|p| p.cpu_usage() as f64).unwrap_or(0.0);
        publish(pct);
    }
}

#[cfg(test)]
mod tests {
    #[test]
    #[cfg(target_os = "linux")]
    fn reads_own_cpu_ticks() {
        let a = super::read_self_cpu_ticks().expect("read /proc/self/stat");
        // Burn a little CPU, then the counter must not go backwards.
        let mut x = 0u64;
        for i in 0..5_000_000u64 {
            x = x.wrapping_add(i);
        }
        assert!(x > 0);
        let b = super::read_self_cpu_ticks().expect("read again");
        assert!(b >= a, "cpu ticks went backwards: {a} -> {b}");
    }
}

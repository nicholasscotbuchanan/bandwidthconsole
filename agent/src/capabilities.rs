//! Host capability probing. The console greys out UI controls based on what the
//! selected agents report here, so honesty matters more than optimism.

use crate::protocol::Capabilities;

/// Whether this build + host can drive DPDK — gates both UDP+DPDK and QUIC+DPDK
/// in the console. Delegates to the datapath module so there is one source of
/// truth. We can't fully prove hugepages/NIC binding without initializing EAL,
/// so the engine re-checks (and fails loudly) at run time.
pub fn dpdk_supported() -> bool {
    crate::engine::dpdk::available()
}

/// DSCP marking via IP_TOS. Settable on Linux/macOS; on Windows the value is
/// often ignored by the stack unless a QoS policy is configured, so we report
/// false there to avoid promising something that silently no-ops.
pub fn dscp_supported() -> bool {
    cfg!(any(target_os = "linux", target_os = "macos"))
}

/// Read whether TCP selective acknowledgements are enabled on this host.
/// On Linux this is `net.ipv4.tcp_sack`; elsewhere SACK is effectively always
/// on in modern stacks, so we report true.
pub fn sack_active() -> bool {
    #[cfg(target_os = "linux")]
    {
        std::fs::read_to_string("/proc/sys/net/ipv4/tcp_sack")
            .map(|s| s.trim() == "1")
            .unwrap_or(true)
    }
    #[cfg(not(target_os = "linux"))]
    {
        true
    }
}

pub fn detect() -> Capabilities {
    let cpu_count = std::thread::available_parallelism()
        .map(|n| n.get() as u32)
        .unwrap_or(1);
    Capabilities {
        dpdk: dpdk_supported(),
        dscp: dscp_supported(),
        sack: sack_active(),
        // Generous ceiling; the source caps offered threads at this.
        max_threads: (cpu_count * 64).max(64),
        cpu_count,
    }
}

pub fn os_name() -> String {
    std::env::consts::OS.to_string()
}

pub fn arch_name() -> String {
    std::env::consts::ARCH.to_string()
}

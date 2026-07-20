//! Where am I running, and how do I reach the host from here?
//!
//! Mixed host/container runs are the case this exists for. A receiver that lives on
//! the Docker host advertises `127.0.0.1`, because that is genuinely how the
//! console reached it. Relayed to a sender inside a container, that address is
//! not merely unhelpful — it is *actively wrong*, naming the container's own
//! loopback, where nothing is listening. That is the `Connection refused` a
//! host↔container pairing hits every time.
//!
//! The sender is the only party that can spot this: a loopback address for a
//! *peer* is a contradiction whenever the peer is, by construction, a different
//! process on a different network namespace. So the sender rewrites it.

use std::net::IpAddr;
use std::sync::OnceLock;

/// Names a container runtime may publish for its host, best first.
///
/// Podman sets `host.containers.internal` itself, with no compose entry needed,
/// so it leads; Docker Desktop sets `host.docker.internal`, which nerdctl and a
/// Linux `--add-host=...:host-gateway` also honour. Trying all of them is how
/// this stays runtime-agnostic — the alias that resolves tells us what we are
/// running under, which beats sniffing for a particular runtime.
const HOST_ALIASES: [&str; 2] = ["host.containers.internal", "host.docker.internal"];

/// Are we running inside a container?
///
/// Cheap, cached, and deliberately conservative: a false positive would rewrite
/// a legitimate single-host loopback run into a gateway address that does not
/// listen, so we only claim yes on positive evidence.
pub fn in_container() -> bool {
    static CACHE: OnceLock<bool> = OnceLock::new();
    *CACHE.get_or_init(detect_container)
}

fn detect_container() -> bool {
    // Docker writes this marker into every container it builds.
    if std::path::Path::new("/.dockerenv").exists() {
        return true;
    }
    // Podman's equivalent.
    if std::path::Path::new("/run/.containerenv").exists() {
        return true;
    }
    // cgroup paths name the runtime under Docker, containerd and Kubernetes.
    if let Ok(s) = std::fs::read_to_string("/proc/1/cgroup") {
        if s.contains("docker") || s.contains("containerd") || s.contains("kubepods") {
            return true;
        }
    }
    false
}

/// The address of the container host, if we can find one.
///
/// Two sources, best first: whichever host alias resolves, then the default
/// route's gateway, which on a bridge network *is* the host. Cached because a
/// failed DNS lookup is not free and the answer cannot change under us.
pub fn host_gateway() -> Option<String> {
    static CACHE: OnceLock<Option<String>> = OnceLock::new();
    CACHE.get_or_init(detect_gateway).clone()
}

fn detect_gateway() -> Option<String> {
    use std::net::ToSocketAddrs;
    // A resolvable alias wins: it survives the host's IP changing.
    for alias in HOST_ALIASES {
        if (alias, 0u16).to_socket_addrs().is_ok() {
            return Some(alias.to_string());
        }
    }
    default_route_gateway().map(|ip| ip.to_string())
}

/// Parse the default gateway out of `/proc/net/route`.
///
/// Columns are tab-separated; a default route has destination `00000000`, and
/// the gateway is a little-endian hex u32 — so `0100FEAC` is 172.254.0.1 read
/// back to front.
fn default_route_gateway() -> Option<IpAddr> {
    let table = std::fs::read_to_string("/proc/net/route").ok()?;
    for line in table.lines().skip(1) {
        let mut cols = line.split_whitespace();
        let _iface = cols.next()?;
        let dest = cols.next()?;
        let gateway = cols.next()?;
        if dest != "00000000" {
            continue;
        }
        let raw = u32::from_str_radix(gateway, 16).ok()?;
        let [a, b, c, d] = raw.to_le_bytes();
        let ip = IpAddr::from([a, b, c, d]);
        if !ip.is_unspecified() {
            return Some(ip);
        }
    }
    None
}

/// Is this host string one that only ever means "me"?
pub fn is_loopback_host(host: &str) -> bool {
    if host.eq_ignore_ascii_case("localhost") {
        return true;
    }
    host.parse::<IpAddr>().map(|ip| ip.is_loopback()).unwrap_or(false)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn loopback_hosts_are_recognised() {
        assert!(is_loopback_host("127.0.0.1"));
        assert!(is_loopback_host("127.0.0.53"));
        assert!(is_loopback_host("localhost"));
        assert!(is_loopback_host("::1"));
        assert!(!is_loopback_host("10.99.0.2"));
        assert!(!is_loopback_host("dpdk-receiver"));
    }
}

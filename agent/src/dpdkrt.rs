//! The real DPDK datapath runtime: EAL bring-up, userspace Ethernet/IPv4/UDP
//! framing, and a poll-mode receive thread that demuxes datagrams to per-port
//! queues.
//!
//! DPDK hands you raw Ethernet frames, so everything the kernel normally does
//! below `send()`/`recv()` has to happen here. Two simplifications make that
//! tractable, and both are honest for a point-to-point benchmark link (memif or
//! a direct NIC pair):
//!   * **No ARP** — frames go to the broadcast MAC; the peer accepts them
//!     because the link has exactly one other end and the port is promiscuous.
//!   * **UDP checksum 0** — explicitly legal for IPv4 and what high-rate
//!     senders do anyway. The IPv4 header checksum *is* computed properly.
//!
//! Without the `dpdk` feature every entry point still exists and returns a
//! clear `Unsupported` error, so the rest of the agent compiles unchanged.

// The framing, rx thread and port registry are only exercised when the feature
// is on; keeping them compiled either way is deliberate, so the datapath stays
// type-checked (and unit-tested) on every platform rather than rotting behind a
// cfg. Silence the resulting dead-code noise in non-DPDK builds.
#![cfg_attr(not(feature = "dpdk"), allow(dead_code))]

use std::collections::HashMap;
use std::collections::VecDeque;
use std::io;
use std::net::{Ipv4Addr, SocketAddr, SocketAddrV4};
use std::sync::atomic::{AtomicU16, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::task::Waker;

pub const ETH_HDR: usize = 14;
pub const IP_HDR: usize = 20;
pub const UDP_HDR: usize = 8;
pub const HDRS: usize = ETH_HDR + IP_HDR + UDP_HDR;
/// Largest frame we will build or accept. Jumbo: the shim configures a 9000
/// MTU and sizes mbufs to match, and the memif vdevs get a matching `bsize`
/// (see docker-compose.yml). All three have to agree or frames get truncated.
pub const MAX_FRAME: usize = 9000 + ETH_HDR;
/// Usable UDP payload per datagram at the full jumbo MTU. This is the ceiling —
/// call [`max_payload`] for what the link actually negotiated.
pub const MAX_PAYLOAD: usize = MAX_FRAME - HDRS;

/// Usable UDP payload for the MTU the port actually came up at. The shim clamps
/// its jumbo request to what the PMD reports it can carry, so on a device that
/// cannot do 9000 this is correspondingly smaller — sizing sends off the
/// constant instead would hand the device frames it silently truncates.
pub fn max_payload() -> usize {
    #[cfg(feature = "dpdk")]
    {
        let mtu = unsafe { ffi::bw_dpdk_mtu() } as usize;
        // 0 means the port is not configured yet; fall back to the ceiling.
        if mtu > IP_HDR + UDP_HDR {
            return (mtu - IP_HDR - UDP_HDR).min(MAX_PAYLOAD);
        }
    }
    MAX_PAYLOAD
}

// ---------------------------------------------------------------- FFI

#[cfg(feature = "dpdk")]
mod ffi {
    extern "C" {
        pub fn bw_dpdk_init(eal_args: *const std::os::raw::c_char) -> i32;
        pub fn bw_dpdk_port_count() -> u16;
        pub fn bw_dpdk_mtu() -> u32;
        pub fn bw_dpdk_port_configure(port: u16) -> i32;
        pub fn bw_dpdk_port_start(port: u16) -> i32;
        pub fn bw_dpdk_mac(port: u16, out6: *mut u8) -> i32;
        pub fn bw_dpdk_tx(port: u16, frame: *const u8, len: u32) -> i32;
        pub fn bw_dpdk_rx(port: u16, out: *mut u8, cap: u32, out_len: *mut u32) -> i32;
        pub fn bw_dpdk_link_up(port: u16) -> i32;
    }
}

fn unsupported<T>() -> io::Result<T> {
    Err(io::Error::new(
        io::ErrorKind::Unsupported,
        if !cfg!(target_os = "linux") {
            "DPDK is Linux-only; this agent's host does not support it"
        } else {
            "this agent was built without the `dpdk` feature; rebuild with \
             --features dpdk on a DPDK-provisioned Linux host"
        },
    ))
}

// ---------------------------------------------------------------- config

#[derive(Clone, Debug)]
pub struct DpdkConfig {
    /// EAL command line, e.g. "bwagent -l 0 --no-huge -m 256 --no-pci --vdev=net_memif0,role=client,id=0,socket=/run/memif.sock"
    pub eal: String,
    /// This agent's address on the DPDK link.
    pub ip: Ipv4Addr,
    /// DPDK port id (usually 0 with a single vdev).
    pub port_id: u16,
}

// ---------------------------------------------------------------- state

struct PortQueue {
    q: Mutex<VecDeque<(SocketAddr, Vec<u8>)>>,
    waker: Mutex<Option<Waker>>,
}

struct State {
    cfg: DpdkConfig,
    mac: [u8; 6],
    ports: Mutex<HashMap<u16, Arc<PortQueue>>>,
}

static STATE: OnceLock<State> = OnceLock::new();
static CONFIG: OnceLock<DpdkConfig> = OnceLock::new();
/// Serialises datapath bring-up (see `ensure_started`).
static INIT_LOCK: Mutex<()> = Mutex::new(());
static NEXT_PORT: AtomicU16 = AtomicU16::new(49152);

/// Record the datapath configuration supplied on the command line. The engines
/// call [`start`] later, only when a run actually asks for a DPDK protocol, so
/// an agent with no DPDK config still serves every other protocol normally.
pub fn configure(cfg: DpdkConfig) {
    let _ = CONFIG.set(cfg);
}

pub fn configured() -> Option<&'static DpdkConfig> {
    CONFIG.get()
}

/// This agent's IP on the DPDK link, if configured — used when advertising a
/// sink address for DPDK runs (the control-plane IP is a different network).
pub fn local_ip() -> Option<Ipv4Addr> {
    CONFIG.get().map(|c| c.ip)
}

/// Bring the configured datapath up.
pub fn start() -> io::Result<()> {
    let cfg = CONFIG.get().ok_or_else(|| {
        io::Error::new(
            io::ErrorKind::InvalidInput,
            "DPDK protocols need --dpdk-eal and --dpdk-ip on this agent",
        )
    })?;
    ensure_started(cfg)
}

/// Bring up EAL + the port + the receive thread. Idempotent; safe to call from
/// each run. EAL can only be initialised once per process, which this enforces.
pub fn ensure_started(cfg: &DpdkConfig) -> io::Result<()> {
    #[cfg(not(feature = "dpdk"))]
    {
        let _ = cfg;
        return unsupported();
    }
    #[cfg(feature = "dpdk")]
    {
        if STATE.get().is_some() {
            return Ok(());
        }
        // EAL can only be initialised once per process, and the eager startup
        // thread can race an incoming run here. Serialise, then re-check:
        // without this both callers pass the guard and the loser's
        // rte_eal_init returns -2.
        let _init_guard = INIT_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        if STATE.get().is_some() {
            return Ok(());
        }
        let c = std::ffi::CString::new(cfg.eal.clone())
            .map_err(|e| io::Error::new(io::ErrorKind::InvalidInput, e))?;
        let rc = unsafe { ffi::bw_dpdk_init(c.as_ptr()) };
        if rc != 0 {
            return Err(io::Error::other(format!(
                "rte_eal_init failed ({rc}); check EAL args, permissions and memory: {}",
                cfg.eal
            )));
        }
        let n = unsafe { ffi::bw_dpdk_port_count() };
        if n == 0 {
            return Err(io::Error::other(
                "DPDK started but no ports are available — pass a --vdev (e.g. net_memif) in the EAL args",
            ));
        }
        let rc = unsafe { ffi::bw_dpdk_port_configure(cfg.port_id) };
        if rc != 0 {
            return Err(io::Error::other(format!(
                "port {} configure failed ({rc})",
                cfg.port_id
            )));
        }
        // Starting a memif *client* dials the server's unix socket, which fails
        // until the peer agent has created it. Retry rather than losing the run
        // to a startup race.
        let mut rc = -1;
        for attempt in 0..40 {
            rc = unsafe { ffi::bw_dpdk_port_start(cfg.port_id) };
            if rc == 0 {
                break;
            }
            if attempt == 0 {
                tracing::info!("waiting for DPDK peer (port start returned {rc})");
            }
            std::thread::sleep(std::time::Duration::from_millis(500));
        }
        if rc != 0 {
            return Err(io::Error::other(format!(
                "port {} start failed ({rc}) after retrying for 20s — is the DPDK peer up? \
                 (for memif, the role=server side must be running)",
                cfg.port_id
            )));
        }
        let mut mac = [0u8; 6];
        unsafe { ffi::bw_dpdk_mac(cfg.port_id, mac.as_mut_ptr()) };

        let st = State {
            cfg: cfg.clone(),
            mac,
            ports: Mutex::new(HashMap::new()),
        };
        if STATE.set(st).is_err() {
            return Ok(()); // raced; another thread won
        }
        spawn_rx_thread(cfg.port_id);
        tracing::info!(
            port = cfg.port_id,
            ip = %cfg.ip,
            mac = ?mac,
            "DPDK datapath up"
        );
        Ok(())
    }
}

/// Is the DPDK link up (memif reports down until its peer connects)?
pub fn link_up() -> bool {
    #[cfg(feature = "dpdk")]
    {
        if let Some(st) = STATE.get() {
            return unsafe { ffi::bw_dpdk_link_up(st.cfg.port_id) } == 1;
        }
    }
    false
}

/// The single poll-mode receive loop: burst, parse, dispatch, repeat.
#[cfg(feature = "dpdk")]
fn spawn_rx_thread(port_id: u16) {
    std::thread::Builder::new()
        .name("dpdk-rx".into())
        .spawn(move || {
            let mut buf = vec![0u8; MAX_FRAME];
            let mut idle: u32 = 0;
            loop {
                let mut len: u32 = 0;
                let got = unsafe {
                    ffi::bw_dpdk_rx(port_id, buf.as_mut_ptr(), MAX_FRAME as u32, &mut len)
                };
                if got != 1 {
                    // Busy-poll briefly, then yield so we don't pin a core at
                    // 100% while a run is idle.
                    idle += 1;
                    if idle > 2048 {
                        std::thread::sleep(std::time::Duration::from_micros(50));
                    } else {
                        std::hint::spin_loop();
                    }
                    continue;
                }
                idle = 0;
                if let Some((src, dst_port, payload)) = parse_frame(&buf[..len as usize]) {
                    dispatch(dst_port, src, payload);
                }
            }
        })
        .expect("spawn dpdk-rx");
}

#[cfg(not(feature = "dpdk"))]
fn spawn_rx_thread(_port_id: u16) {}

fn dispatch(dst_port: u16, src: SocketAddr, payload: Vec<u8>) {
    let Some(st) = STATE.get() else { return };
    let q = {
        let ports = st.ports.lock().unwrap();
        match ports.get(&dst_port) {
            Some(q) => q.clone(),
            None => return, // nothing bound to that port; drop
        }
    };
    {
        let mut dq = q.q.lock().unwrap();
        // Bound the queue so a stalled reader can't consume all memory.
        if dq.len() < 8192 {
            dq.push_back((src, payload));
        }
    }
    // Bind before waking: holding the guard inside the `if let` would keep the
    // borrow alive past the end of this scope.
    let waker = q.waker.lock().unwrap().take();
    if let Some(w) = waker {
        w.wake();
    }
}

// ---------------------------------------------------------------- handle

/// A bound UDP port on the DPDK datapath.
pub struct DpdkPort {
    pub local: SocketAddr,
    queue: Arc<PortQueue>,
}

/// Bind a local UDP port (0 = ephemeral) on the DPDK link.
pub fn bind(local_port: u16) -> io::Result<DpdkPort> {
    let Some(st) = STATE.get() else {
        return unsupported();
    };
    let port = if local_port == 0 {
        NEXT_PORT.fetch_add(1, Ordering::Relaxed).max(49152)
    } else {
        local_port
    };
    let q = Arc::new(PortQueue {
        q: Mutex::new(VecDeque::new()),
        waker: Mutex::new(None),
    });
    st.ports.lock().unwrap().insert(port, q.clone());
    Ok(DpdkPort {
        local: SocketAddr::V4(SocketAddrV4::new(st.cfg.ip, port)),
        queue: q,
    })
}

impl DpdkPort {
    /// Non-blocking receive.
    pub fn try_recv(&self) -> Option<(SocketAddr, Vec<u8>)> {
        self.queue.q.lock().unwrap().pop_front()
    }

    /// Park `waker` to be woken when a datagram arrives.
    pub fn register(&self, waker: Waker) {
        *self.queue.waker.lock().unwrap() = Some(waker);
    }

    /// Frame and transmit one datagram.
    pub fn send_to(&self, dst: SocketAddr, payload: &[u8]) -> io::Result<()> {
        let Some(st) = STATE.get() else {
            return unsupported();
        };
        let SocketAddr::V4(dst4) = dst else {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "DPDK datapath is IPv4-only",
            ));
        };
        let cap = max_payload();
        if payload.len() > cap {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                format!("datagram {} > max {}", payload.len(), cap),
            ));
        }
        let src_port = match self.local {
            SocketAddr::V4(a) => a.port(),
            _ => 0,
        };
        let mut frame = [0u8; MAX_FRAME];
        let n = build_frame(
            &mut frame,
            st.mac,
            st.cfg.ip,
            src_port,
            *dst4.ip(),
            dst4.port(),
            payload,
        );
        #[cfg(feature = "dpdk")]
        {
            let rc = unsafe { ffi::bw_dpdk_tx(st.cfg.port_id, frame.as_ptr(), n as u32) };
            return match rc {
                1 => Ok(()),
                0 => Err(io::Error::new(io::ErrorKind::WouldBlock, "tx ring full")),
                e => Err(io::Error::other(format!("tx failed ({e})"))),
            };
        }
        #[cfg(not(feature = "dpdk"))]
        {
            let _ = n;
            unsupported()
        }
    }
}

impl Drop for DpdkPort {
    fn drop(&mut self) {
        if let Some(st) = STATE.get() {
            if let SocketAddr::V4(a) = self.local {
                st.ports.lock().unwrap().remove(&a.port());
            }
        }
    }
}

// ---------------------------------------------------------------- framing

fn ip_checksum(hdr: &[u8]) -> u16 {
    let mut sum: u32 = 0;
    let mut i = 0;
    while i + 1 < hdr.len() {
        sum += u16::from_be_bytes([hdr[i], hdr[i + 1]]) as u32;
        i += 2;
    }
    if i < hdr.len() {
        sum += (hdr[i] as u32) << 8;
    }
    while (sum >> 16) != 0 {
        sum = (sum & 0xFFFF) + (sum >> 16);
    }
    !(sum as u16)
}

/// Build Ethernet + IPv4 + UDP around `payload`. Returns the frame length.
pub fn build_frame(
    out: &mut [u8],
    src_mac: [u8; 6],
    src_ip: Ipv4Addr,
    src_port: u16,
    dst_ip: Ipv4Addr,
    dst_port: u16,
    payload: &[u8],
) -> usize {
    let total = HDRS + payload.len();

    // Ethernet: broadcast destination (point-to-point link, no ARP needed).
    out[0..6].copy_from_slice(&[0xFF; 6]);
    out[6..12].copy_from_slice(&src_mac);
    out[12..14].copy_from_slice(&0x0800u16.to_be_bytes());

    // IPv4
    let ip = &mut out[ETH_HDR..ETH_HDR + IP_HDR];
    ip[0] = 0x45; // v4, IHL 5
    ip[1] = 0; // DSCP/ECN
    ip[2..4].copy_from_slice(&((IP_HDR + UDP_HDR + payload.len()) as u16).to_be_bytes());
    ip[4..6].copy_from_slice(&0u16.to_be_bytes()); // id
    ip[6..8].copy_from_slice(&0x4000u16.to_be_bytes()); // DF
    ip[8] = 64; // TTL
    ip[9] = 17; // UDP
    ip[10..12].copy_from_slice(&0u16.to_be_bytes()); // checksum placeholder
    ip[12..16].copy_from_slice(&src_ip.octets());
    ip[16..20].copy_from_slice(&dst_ip.octets());
    let csum = ip_checksum(&out[ETH_HDR..ETH_HDR + IP_HDR]);
    out[ETH_HDR + 10..ETH_HDR + 12].copy_from_slice(&csum.to_be_bytes());

    // UDP (checksum 0 = not computed; legal for IPv4)
    let u = ETH_HDR + IP_HDR;
    out[u..u + 2].copy_from_slice(&src_port.to_be_bytes());
    out[u + 2..u + 4].copy_from_slice(&dst_port.to_be_bytes());
    out[u + 4..u + 6].copy_from_slice(&((UDP_HDR + payload.len()) as u16).to_be_bytes());
    out[u + 6..u + 8].copy_from_slice(&0u16.to_be_bytes());

    out[HDRS..total].copy_from_slice(payload);
    total
}

/// Parse a frame, returning (source addr, destination UDP port, payload).
pub fn parse_frame(frame: &[u8]) -> Option<(SocketAddr, u16, Vec<u8>)> {
    if frame.len() < HDRS {
        return None;
    }
    if u16::from_be_bytes([frame[12], frame[13]]) != 0x0800 {
        return None; // not IPv4
    }
    let ip = &frame[ETH_HDR..];
    if ip[0] >> 4 != 4 {
        return None;
    }
    let ihl = ((ip[0] & 0x0F) as usize) * 4;
    if ihl < 20 || frame.len() < ETH_HDR + ihl + UDP_HDR {
        return None;
    }
    if ip[9] != 17 {
        return None; // not UDP
    }
    let total_len = u16::from_be_bytes([ip[2], ip[3]]) as usize;
    let src_ip = Ipv4Addr::new(ip[12], ip[13], ip[14], ip[15]);

    let u = ETH_HDR + ihl;
    let src_port = u16::from_be_bytes([frame[u], frame[u + 1]]);
    let dst_port = u16::from_be_bytes([frame[u + 2], frame[u + 3]]);
    let udp_len = u16::from_be_bytes([frame[u + 4], frame[u + 5]]) as usize;

    // Trust the smaller of the declared lengths and what actually arrived.
    let payload_len = udp_len
        .saturating_sub(UDP_HDR)
        .min(total_len.saturating_sub(ihl + UDP_HDR))
        .min(frame.len() - (u + UDP_HDR));
    let start = u + UDP_HDR;
    let payload = frame[start..start + payload_len].to_vec();
    Some((
        SocketAddr::V4(SocketAddrV4::new(src_ip, src_port)),
        dst_port,
        payload,
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn frame_roundtrip() {
        let mut buf = [0u8; MAX_FRAME];
        let payload = b"hello dpdk";
        let n = build_frame(
            &mut buf,
            [0x02, 0, 0, 0, 0, 1],
            Ipv4Addr::new(10, 99, 0, 1),
            4433,
            Ipv4Addr::new(10, 99, 0, 2),
            5555,
            payload,
        );
        assert_eq!(n, HDRS + payload.len());
        let (src, dst_port, got) = parse_frame(&buf[..n]).expect("parse");
        assert_eq!(src, "10.99.0.1:4433".parse().unwrap());
        assert_eq!(dst_port, 5555);
        assert_eq!(got, payload);
    }

    #[test]
    fn ipv4_checksum_is_valid() {
        // A correct IPv4 header checksums (including the checksum field) to 0.
        let mut buf = [0u8; MAX_FRAME];
        let n = build_frame(
            &mut buf,
            [0x02, 0, 0, 0, 0, 1],
            Ipv4Addr::new(192, 168, 1, 1),
            1,
            Ipv4Addr::new(192, 168, 1, 2),
            2,
            b"x",
        );
        assert!(n > 0);
        assert_eq!(ip_checksum(&buf[ETH_HDR..ETH_HDR + IP_HDR]), 0);
    }

    #[test]
    fn rejects_non_udp() {
        let mut buf = [0u8; MAX_FRAME];
        let n = build_frame(
            &mut buf,
            [0; 6],
            Ipv4Addr::LOCALHOST,
            1,
            Ipv4Addr::LOCALHOST,
            2,
            b"x",
        );
        buf[ETH_HDR + 9] = 6; // TCP
        assert!(parse_frame(&buf[..n]).is_none());
    }
}

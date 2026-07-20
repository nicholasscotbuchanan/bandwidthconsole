//! Control-channel client. The agent dials out to the console (the nexus),
//! registers its capabilities, then serves commands: become a receiver, become a
//! sender, or abort. Telemetry and results flow back up the same TCP stream as
//! newline-delimited JSON.

use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;

use anyhow::{Context, Result};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::TcpStream;
use tokio::sync::mpsc;
use tokio::sync::Mutex;

use crate::engine::Stop;
use crate::procgroup;
use crate::protocol::{AgentMsg, Capabilities, ConsoleMsg};

pub struct AgentConfig {
    pub console_addr: String,
    pub name: String,
    pub agent_id: String,
    /// Operator override for the data-plane host, as a comma-separated list of
    /// hosts, best first. `None` means "work it out from the control
    /// connection", which is right far more often than any default.
    ///
    /// A list rather than one value because reachability is not a property of
    /// this agent alone: a containerised receiver is `edge1` to its network peers
    /// and `127.0.0.1:<published>` to the host, and both are true at once.
    pub advertise_ip: Option<String>,
    /// The port peers should dial, when that differs from the one we bind —
    /// i.e. a container port mapping that is not 1:1. `None` means "same port".
    pub advertise_port: Option<u16>,
    pub caps: Capabilities,
}

/// Per-run stop flags so Abort can target either end.
#[derive(Default)]
struct Runs {
    receivers: HashMap<String, Stop>,
    senders: HashMap<String, Stop>,
}

/// Connect once and serve until the connection drops. The caller reconnects.
pub async fn serve(cfg: &AgentConfig) -> Result<()> {
    let stream = TcpStream::connect(&cfg.console_addr)
        .await
        .with_context(|| format!("connect console {}", cfg.console_addr))?;
    stream.set_nodelay(true).ok();

    // The local address of this socket is the IP the console just reached us on,
    // so it is a far better default advertise host than loopback: it is by
    // construction routable from at least one other box on the test fabric.
    let advertise = cfg.advertise_hosts(stream.local_addr().ok().map(|a| a.ip()));
    tracing::info!(hosts = %advertise.join(","), "advertising data plane");

    let (rd, mut wr) = stream.into_split();

    let (tx, mut rx) = mpsc::unbounded_channel::<AgentMsg>();

    // Writer task: serialize outbound messages as NDJSON.
    let writer = tokio::spawn(async move {
        while let Some(msg) = rx.recv().await {
            if let Ok(mut s) = serde_json::to_string(&msg) {
                s.push('\n');
                if wr.write_all(s.as_bytes()).await.is_err() {
                    break;
                }
            }
        }
    });

    // Register.
    tx.send(AgentMsg::Register {
        agent_id: cfg.agent_id.clone(),
        name: cfg.name.clone(),
        os: crate::capabilities::os_name(),
        arch: crate::capabilities::arch_name(),
        data_addr: advertise.join(","),
        capabilities: cfg.caps.clone(),
    })?;

    // Heartbeat.
    {
        let tx = tx.clone();
        let id = cfg.agent_id.clone();
        tokio::spawn(async move {
            let mut t = tokio::time::interval(Duration::from_secs(5));
            loop {
                t.tick().await;
                if tx.send(AgentMsg::Heartbeat { agent_id: id.clone() }).is_err() {
                    break;
                }
            }
        });
    }

    let runs = Arc::new(Mutex::new(Runs::default()));
    let mut lines = BufReader::new(rd).lines();
    while let Some(line) = lines.next_line().await? {
        let line = line.trim();
        if line.is_empty() {
            continue;
        }
        let msg: ConsoleMsg = match serde_json::from_str(line) {
            Ok(m) => m,
            Err(e) => {
                tracing::warn!("bad console message: {e}: {line}");
                continue;
            }
        };
        handle(msg, &advertise, cfg.advertise_port, &tx, &runs).await;
    }

    writer.abort();
    Ok(())
}

async fn handle(
    msg: ConsoleMsg,
    advertise: &[String],
    advertise_port: Option<u16>,
    tx: &mpsc::UnboundedSender<AgentMsg>,
    runs: &Arc<Mutex<Runs>>,
) {
    match msg {
        ConsoleMsg::PrepareReceive { run_id, scenario } => {
            // DPDK runs live on their own userspace network, so the receiver must
            // advertise its DPDK address rather than the control-plane one.
            let needs_dpdk = scenario.protocol.needs_dpdk();
            match crate::engine::run_recv(run_id.clone(), scenario, tx.clone()).await {
                Ok((local, stop)) => {
                    runs.lock().await.receivers.insert(run_id.clone(), stop);
                    let listen_addr = if needs_dpdk {
                        local.to_string() // already dpdk_ip:port
                    } else {
                        // One entry per way we might be reachable; the sender
                        // tries them in order and takes the one that answers.
                        let port = advertise_port.unwrap_or_else(|| local.port());
                        advertise
                            .iter()
                            .map(|h| format!("{h}:{port}"))
                            .collect::<Vec<_>>()
                            .join(",")
                    };
                    let _ = tx.send(AgentMsg::ReceiveReady { run_id, listen_addr });
                }
                Err(e) => {
                    let _ = tx.send(AgentMsg::RunError {
                        run_id,
                        message: format!("receiver bind failed: {e}"),
                    });
                }
            }
        }
        ConsoleMsg::StartSend {
            run_id,
            scenario,
            target_addr,
        } => {
            let stop: Stop = Arc::new(AtomicBool::new(false));
            runs.lock().await.senders.insert(run_id.clone(), stop.clone());
            let tx = tx.clone();
            tokio::spawn(async move {
                match procgroup::run_send(run_id.clone(), scenario, target_addr, tx.clone(), stop)
                    .await
                {
                    Ok(summary) => {
                        let _ = tx.send(AgentMsg::RunComplete { summary });
                    }
                    Err(e) => {
                        let _ = tx.send(AgentMsg::RunError {
                            run_id,
                            message: format!("{e:#}"),
                        });
                    }
                }
            });
        }
        ConsoleMsg::Abort { run_id } => {
            let g = runs.lock().await;
            if let Some(s) = g.senders.get(&run_id) {
                s.store(true, Ordering::Relaxed);
            }
            if let Some(s) = g.receivers.get(&run_id) {
                s.store(true, Ordering::Relaxed);
            }
        }
        ConsoleMsg::Pong => {}
    }
}

impl AgentConfig {
    /// The hosts other agents should try to reach this one's data plane, best
    /// first.
    ///
    /// An explicit `--advertise` always wins, and may list several hosts —
    /// each stripped of any accidental port. Otherwise we fall back to `local`,
    /// the control socket's local address: the one address we have positive
    /// evidence is reachable from off-box.
    ///
    /// Loopback is the last resort. It is honest for a single-host run and a
    /// lie for every other one, so when we are reduced to it we also offer the
    /// gateway — that covers a *containerised* agent whose peers sit on the
    /// host. The reverse case, a host agent with containerised peers, cannot be
    /// fixed from here: the sender is the only side that knows it is boxed in,
    /// and it rewrites loopback itself (see `engine::candidates`).
    fn advertise_hosts(&self, local: Option<std::net::IpAddr>) -> Vec<String> {
        if let Some(a) = &self.advertise_ip {
            let hosts: Vec<String> = a
                .split(',')
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .map(|s| s.split(':').next().unwrap_or(s).to_string())
                .collect();
            if !hosts.is_empty() {
                return hosts;
            }
        }
        match local {
            Some(ip) if !ip.is_loopback() && !ip.is_unspecified() => vec![ip.to_string()],
            _ => {
                let mut hosts = vec!["127.0.0.1".to_string()];
                if crate::netenv::in_container() {
                    hosts.extend(crate::netenv::host_gateway());
                }
                hosts
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn cfg(advertise: Option<&str>) -> AgentConfig {
        AgentConfig {
            console_addr: "127.0.0.1:9077".into(),
            name: "t".into(),
            agent_id: "id".into(),
            advertise_ip: advertise.map(str::to_string),
            advertise_port: None,
            caps: crate::capabilities::detect(),
        }
    }

    #[test]
    fn explicit_advertise_may_list_several_hosts() {
        let hosts = cfg(Some("edge1, 127.0.0.1")).advertise_hosts(None);
        assert_eq!(hosts, vec!["edge1", "127.0.0.1"]);
    }

    #[test]
    fn ports_are_stripped_from_advertised_hosts() {
        let hosts = cfg(Some("edge1:9999")).advertise_hosts(None);
        assert_eq!(hosts, vec!["edge1"]);
    }

    #[test]
    fn routable_control_address_beats_loopback() {
        let local = Some("192.168.1.5".parse().unwrap());
        assert_eq!(cfg(None).advertise_hosts(local), vec!["192.168.1.5"]);
    }
}

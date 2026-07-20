//! Control-channel client. The agent dials out to the console (the nexus),
//! registers its capabilities, then serves commands: become a sink, become a
//! source, or abort. Telemetry and results flow back up the same TCP stream as
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
    pub advertise_ip: String,
    pub caps: Capabilities,
}

/// Per-run stop flags so Abort can target either role.
#[derive(Default)]
struct Runs {
    sinks: HashMap<String, Stop>,
    sources: HashMap<String, Stop>,
}

/// Connect once and serve until the connection drops. The caller reconnects.
pub async fn serve(cfg: &AgentConfig) -> Result<()> {
    let stream = TcpStream::connect(&cfg.console_addr)
        .await
        .with_context(|| format!("connect console {}", cfg.console_addr))?;
    stream.set_nodelay(true).ok();
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
        data_addr: cfg.advertise_ip.clone(),
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
        handle(msg, cfg, &tx, &runs).await;
    }

    writer.abort();
    Ok(())
}

async fn handle(
    msg: ConsoleMsg,
    cfg: &AgentConfig,
    tx: &mpsc::UnboundedSender<AgentMsg>,
    runs: &Arc<Mutex<Runs>>,
) {
    match msg {
        ConsoleMsg::PrepareSink { run_id, scenario } => {
            // DPDK runs live on their own userspace network, so the sink must
            // advertise its DPDK address rather than the control-plane one.
            let needs_dpdk = scenario.protocol.needs_dpdk();
            match crate::engine::run_sink(run_id.clone(), scenario, tx.clone()).await {
                Ok((local, stop)) => {
                    runs.lock().await.sinks.insert(run_id.clone(), stop);
                    let listen_addr = if needs_dpdk {
                        local.to_string() // already dpdk_ip:port
                    } else {
                        format!("{}:{}", cfg.advertise_ip_host(), local.port())
                    };
                    let _ = tx.send(AgentMsg::RoleReady { run_id, listen_addr });
                }
                Err(e) => {
                    let _ = tx.send(AgentMsg::RunError {
                        run_id,
                        message: format!("sink bind failed: {e}"),
                    });
                }
            }
        }
        ConsoleMsg::StartSource {
            run_id,
            scenario,
            target_addr,
        } => {
            let stop: Stop = Arc::new(AtomicBool::new(false));
            runs.lock().await.sources.insert(run_id.clone(), stop.clone());
            let tx = tx.clone();
            tokio::spawn(async move {
                match procgroup::run_source(run_id.clone(), scenario, target_addr, tx.clone(), stop)
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
            if let Some(s) = g.sources.get(&run_id) {
                s.store(true, Ordering::Relaxed);
            }
            if let Some(s) = g.sinks.get(&run_id) {
                s.store(true, Ordering::Relaxed);
            }
        }
        ConsoleMsg::Pong => {}
    }
}

impl AgentConfig {
    /// The host portion agents should use to reach this one (advertise IP without
    /// any accidental port).
    fn advertise_ip_host(&self) -> String {
        self.advertise_ip
            .split(':')
            .next()
            .unwrap_or(&self.advertise_ip)
            .to_string()
    }
}

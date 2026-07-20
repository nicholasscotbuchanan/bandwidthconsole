//! bwagent — the bandwidth-test surface.
//!
//! One binary, two hats. In its default mode it dials the JavaFX console and
//! serves test runs (as sender or receiver, assigned per run). The hidden `worker`
//! subcommand is one process-share of a multi-process sender; the lead spawns it
//! and merges its NDJSON telemetry (see `procgroup`).

mod capabilities;
mod dpdkrt;
mod control;
mod engine;
mod frametest_cli;
mod netenv;
mod procgroup;
mod protocol;
mod sysstat;
mod tls;

use std::sync::atomic::AtomicBool;
use std::sync::Arc;
use std::time::Duration;

use anyhow::Result;
use clap::{Parser, Subcommand};

use protocol::{AgentMsg, Scenario};

#[derive(Parser)]
#[command(name = "bwagent", version, about = "Bandwidth test surface (one binary; any agent can send to any other)")]
struct Cli {
    /// Console (control plane) address to dial, host:port.
    #[arg(long, env = "BW_CONSOLE", default_value = "127.0.0.1:9077")]
    console: String,

    /// Friendly agent name shown in the console.
    #[arg(long, env = "BW_NAME")]
    name: Option<String>,

    /// Host(s) other agents use to reach this one's data plane, comma-separated
    /// and best first. Defaults to the local address of the control connection
    /// — i.e. the IP the console already reached us on, which beats guessing at
    /// loopback.
    ///
    /// A list, because an agent can be reachable by more than one name at once:
    /// a container published to the host is `--advertise "edge1,127.0.0.1"`,
    /// meaning "my network peers dial edge1, the host dials its own loopback".
    #[arg(long, env = "BW_ADVERTISE")]
    advertise: Option<String>,

    /// Fixed port or range (`57001` or `57001-57008`) for the data plane,
    /// instead of an ephemeral port.
    ///
    /// Required to reach a receiver through a container port mapping: a published
    /// port is fixed when the container starts, so it can never forward a port
    /// picked per run. Pair with `-p <port>:<port>`. Prefer a range — each
    /// concurrent run as receiver takes one port, and a lone port limits you to one.
    #[arg(long, env = "BW_DATA_PORT", default_value = "0")]
    data_port: String,

    /// Port peers should dial, when the mapping is not 1:1 (e.g. `-p 6000:5000`
    /// means `--data-port 5000 --advertise-port 6000`). Defaults to --data-port.
    #[arg(long, env = "BW_ADVERTISE_PORT")]
    advertise_port: Option<u16>,

    /// DPDK EAL command line, enabling the kernel-bypass protocols. Example:
    /// --dpdk-eal "bwagent -l 0 --no-huge -m 256 --no-pci --vdev=net_memif0,role=client,id=0,socket=/run/memif/memif.sock"
    #[arg(long, env = "BW_DPDK_EAL")]
    dpdk_eal: Option<String>,

    /// This agent's IPv4 address on the DPDK link (its own userspace stack).
    #[arg(long, env = "BW_DPDK_IP")]
    dpdk_ip: Option<std::net::Ipv4Addr>,

    /// DPDK port id (usually 0 with a single vdev).
    #[arg(long, env = "BW_DPDK_PORT", default_value = "0")]
    dpdk_port: u16,

    #[command(subcommand)]
    cmd: Option<Cmd>,
}

#[derive(Subcommand)]
enum Cmd {
    /// Internal: run one process-share of a sender and stream NDJSON telemetry.
    Worker {
        #[arg(long)]
        run_id: String,
        #[arg(long)]
        target: String,
        /// Scenario as JSON.
        #[arg(long)]
        scenario: String,
    },
    /// Local storage test with DVS frametest's flags and report format.
    ///
    /// Existing frametest command lines work verbatim, e.g.
    ///   bwagent frametest -w 4k -n 3000 -t 4 /mnt/san/TEST
    ///
    /// Arguments are passed through untouched, so clap must not try to
    /// interpret them — frametest's grammar is not clap's.
    #[command(disable_help_flag = true, allow_hyphen_values = true)]
    Frametest {
        #[arg(trailing_var_arg = true)]
        args: Vec<String>,
    },
}

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "bwagent=info".into()),
        )
        .with_writer(std::io::stderr) // keep stdout clean for worker NDJSON
        .init();

    // Process-wide rustls crypto provider (used by TLS-on-TCP and QUIC).
    let _ = rustls::crypto::ring::default_provider().install_default();

    let cli = Cli::parse();

    // Receivers bind from this range for the data plane; 0 keeps the ephemeral default.
    let (first_port, last_port) = parse_port_range(&cli.data_port)?;
    engine::set_recv_ports(first_port, last_port);

    // Record the DPDK datapath config if supplied. EAL is not started here —
    // only when a run actually selects a DPDK protocol.
    if let (Some(eal), Some(ip)) = (cli.dpdk_eal.clone(), cli.dpdk_ip) {
        dpdkrt::configure(dpdkrt::DpdkConfig {
            eal,
            ip,
            port_id: cli.dpdk_port,
        });
        tracing::info!(%ip, "DPDK datapath configured");
        // Bring the datapath up now rather than lazily on the first DPDK run:
        // a memif server must be listening before its client can attach, and
        // link negotiation shouldn't eat into a measured run. Failure here is
        // not fatal — the agent still serves every non-DPDK protocol.
        std::thread::spawn(|| match dpdkrt::start() {
            Ok(()) => tracing::info!("DPDK datapath ready"),
            Err(e) => tracing::warn!("DPDK datapath unavailable: {e}"),
        });
    }

    match cli.cmd {
        Some(Cmd::Worker {
            run_id,
            target,
            scenario,
        }) => return run_worker(run_id, target, scenario).await,
        // Blocking filesystem work; keep it off the async runtime's threads.
        Some(Cmd::Frametest { args }) => {
            return tokio::task::spawn_blocking(move || frametest_cli::run(&args)).await?
        }
        None => {}
    }

    // Agent mode: connect, serve, reconnect on drop.
    let caps = capabilities::detect();
    let name = cli.name.unwrap_or_else(|| {
        hostname::get()
            .ok()
            .and_then(|h| h.into_string().ok())
            .unwrap_or_else(|| "agent".into())
    });
    let cfg = control::AgentConfig {
        console_addr: cli.console.clone(),
        name,
        agent_id: uuid::Uuid::new_v4().to_string(),
        advertise_ip: cli.advertise.clone().filter(|s| !s.trim().is_empty()),
        advertise_port: cli.advertise_port,
        caps,
    };

    tracing::info!(console = %cfg.console_addr, name = %cfg.name, "bwagent starting");
    loop {
        if let Err(e) = control::serve(&cfg).await {
            tracing::warn!("control disconnected: {e:#}");
        } else {
            tracing::info!("control closed; reconnecting");
        }
        tokio::time::sleep(Duration::from_secs(2)).await;
    }
}

/// Parse `--data-port`: `0` (ephemeral), `57001`, or `57001-57008`.
fn parse_port_range(s: &str) -> Result<(u16, u16)> {
    let s = s.trim();
    let (first, last) = match s.split_once('-') {
        Some((a, b)) => (a.trim(), b.trim()),
        None => (s, s),
    };
    let first: u16 = first
        .parse()
        .map_err(|_| anyhow::anyhow!("--data-port: {first:?} is not a port number"))?;
    let last: u16 = last
        .parse()
        .map_err(|_| anyhow::anyhow!("--data-port: {last:?} is not a port number"))?;
    if last < first {
        anyhow::bail!("--data-port: range {first}-{last} ends before it starts");
    }
    Ok((first, last))
}

/// One process-share: run the engine and print every message as an NDJSON line
/// on stdout for the lead process to merge.
async fn run_worker(run_id: String, target: String, scenario: String) -> Result<()> {
    let sc: Scenario = serde_json::from_str(&scenario)?;
    let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<AgentMsg>();

    let printer = tokio::spawn(async move {
        let mut out = tokio::io::stdout();
        use tokio::io::AsyncWriteExt;
        while let Some(msg) = rx.recv().await {
            if let Ok(mut s) = serde_json::to_string(&msg) {
                s.push('\n');
                let _ = out.write_all(s.as_bytes()).await;
                let _ = out.flush().await;
            }
        }
    });

    let stop = Arc::new(AtomicBool::new(false));
    let summary = engine::run_send(run_id.clone(), sc, target, tx.clone(), stop).await;
    match summary {
        Ok(s) => {
            let _ = tx.send(AgentMsg::RunComplete { summary: s });
        }
        Err(e) => {
            let _ = tx.send(AgentMsg::RunError {
                run_id,
                message: format!("{e:#}"),
            });
        }
    }
    drop(tx);
    let _ = printer.await;
    Ok(())
}

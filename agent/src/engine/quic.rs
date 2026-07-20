//! QUIC data plane (always TLS 1.3, via quinn). The reference tool's headline
//! protocol: QUIC vs TCP+SACK.
//!
//! The sender opens unidirectional streams and blasts; the receiver accepts and
//! drains them. `single_connection` decides whether all streams ride one
//! connection (QUIC's multiplexing story) or each gets its own connection. RTT
//! comes from quinn's own path estimate, and the QUIC/TLS handshake time is
//! recorded as the connect+handshake phases. QUIC is inherently async, so the
//! Threaded/Selector toggle does not apply here.

use std::net::SocketAddr;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

use anyhow::{Context, Result};
use quinn::crypto::rustls::{QuicClientConfig, QuicServerConfig};
use quinn::{ClientConfig, Endpoint, EndpointConfig, ServerConfig, TransportConfig};

use super::{frame, sample_loop, Counters, PhaseTimer, Stop, Tx};
use crate::protocol::{Protocol, Scenario};

const CHUNK: usize = 64 * 1024;

/// Does this scenario want the DPDK datapath under QUIC?
fn wants_dpdk(sc: &Scenario) -> bool {
    matches!(sc.protocol, Protocol::QuicDpdk)
}

/// Build a client endpoint on either kernel UDP or the DPDK datapath. The QUIC
/// stack above is byte-for-byte the same either way — only the transport differs.
fn client_endpoint(sc: &Scenario) -> Result<Endpoint> {
    let bind: SocketAddr = "0.0.0.0:0".parse().unwrap();
    if wants_dpdk(sc) {
        let socket = super::dpdk::DpdkUdpSocket::bind(bind)
            .with_context(|| format!("QUIC+DPDK: {}", super::dpdk::unavailable_reason()))?;
        Ok(Endpoint::new_with_abstract_socket(
            EndpointConfig::default(),
            None,
            socket,
            quinn::default_runtime().context("no async runtime")?,
        )?)
    } else {
        Ok(Endpoint::client(bind)?)
    }
}

/// Same for the receiver side.
fn server_endpoint(sc: &Scenario, server_cfg: ServerConfig) -> Result<Endpoint> {
    super::bind_recv(|bind| server_endpoint_at(sc, server_cfg.clone(), bind))
}

fn server_endpoint_at(sc: &Scenario, server_cfg: ServerConfig, bind: SocketAddr) -> Result<Endpoint> {
    if wants_dpdk(sc) {
        let socket = super::dpdk::DpdkUdpSocket::bind(bind)
            .with_context(|| format!("QUIC+DPDK: {}", super::dpdk::unavailable_reason()))?;
        Ok(Endpoint::new_with_abstract_socket(
            EndpointConfig::default(),
            Some(server_cfg),
            socket,
            quinn::default_runtime().context("no async runtime")?,
        )?)
    } else {
        Ok(Endpoint::server(server_cfg, bind)?)
    }
}

fn transport() -> Arc<TransportConfig> {
    let mut t = TransportConfig::default();
    // Wide flow-control windows so throughput isn't window-bound on fast links.
    t.stream_receive_window((16u32 * 1024 * 1024).into());
    t.receive_window((256u32 * 1024 * 1024).into());
    t.send_window(256 * 1024 * 1024);
    t.max_concurrent_uni_streams(4096u32.into());
    t.max_idle_timeout(Some(Duration::from_secs(30).try_into().unwrap()));
    Arc::new(t)
}

pub async fn run_send(
    run_id: String,
    sc: Scenario,
    target_addr: String,
    tx: Tx,
    stop: Stop,
) -> Result<crate::protocol::RunSummary> {
    let addr: SocketAddr = super::resolve(&target_addr)?;
    let streams = sc.threads.max(1) as usize;
    let counters = Counters::new(streams);
    let mut timer = PhaseTimer::start();
    let first_rtt = Arc::new(AtomicU64::new(0));

    // On DPDK the link is only usable once the memif peer has attached; give it
    // a moment rather than failing the first handshake attempt.
    if wants_dpdk(&sc) {
        super::dpdk::await_link(std::time::Duration::from_secs(10)).await?;
    }

    // Client endpoint (kernel UDP or DPDK, per protocol).
    let mut endpoint = client_endpoint(&sc)?;
    let crypto = QuicClientConfig::try_from((*crate::tls::client_config()).clone())?;
    let mut client_cfg = ClientConfig::new(Arc::new(crypto));
    client_cfg.transport_config(transport());
    endpoint.set_default_client_config(client_cfg);

    let payload = Arc::new(vec![0xABu8; sc.payload_bytes.max(CHUNK as u32) as usize]);
    let connect_start = Instant::now();

    // Establish connection(s).
    let mut conns = Vec::new();
    let n_conns = if sc.single_connection { 1 } else { streams };
    for _ in 0..n_conns {
        let conn = endpoint
            .connect(addr, "localhost")?
            .await
            .context("quic connect")?;
        conns.push(conn);
    }
    timer.connect_ms = connect_start.elapsed().as_secs_f64() * 1000.0;
    timer.handshake_ms = timer.connect_ms; // QUIC folds transport+TLS into connect

    // RTT publisher from quinn's path estimate.
    {
        let conn = conns[0].clone();
        let (c, s, fr) = (counters.clone(), stop.clone(), first_rtt.clone());
        tokio::spawn(async move {
            while !s.load(Ordering::Relaxed) {
                let rtt_us = conn.rtt().as_micros() as u64;
                if rtt_us > 0 {
                    c.rtt_us.store(rtt_us, Ordering::Relaxed);
                    let _ = fr.compare_exchange(0, rtt_us, Ordering::Relaxed, Ordering::Relaxed);
                }
                tokio::time::sleep(Duration::from_millis(200)).await;
            }
        });
    }

    // Multi-file mode maps one frame to one QUIC stream — which is precisely the
    // case stream multiplexing was designed for, and the most interesting
    // protocol comparison the tool can make: TCP pays head-of-line blocking
    // across a frame sequence where QUIC does not.
    if sc.is_multi_file() {
        let plan = frame::validate(&sc)?;
        frame::apply_pause(&plan.spec);
        let frames = frame::FrameCounters::new();
        let queue = frame::FrameQueue::new(plan.spec.queue_depth as usize);
        let frame_target = plan.total();
        let lanes = frame::Lanes::new(frame_target);
        timer.first_byte_ms = first_rtt.load(Ordering::Relaxed) as f64 / 1000.0;

        // Stage the frame set up front, same as the TCP path: the run measures
        // moving frames, not creating them.
        {
            let (pl, ln, st, tx2, rid) =
                (plan.clone(), lanes.clone(), stop.clone(), tx.clone(), run_id.clone());
            let staged = tokio::task::spawn_blocking(move || {
                // `None` until the first emission, so the opening 0/total goes out
                // at once instead of waiting a tick.
                let mut last: Option<Instant> = None;
                let r = frame::pregenerate(&pl, &ln, &st, || {
                    if last.is_none_or(|t| t.elapsed() >= Duration::from_millis(200)) {
                        last = Some(Instant::now());
                        if let Some(u) = ln.snapshot(frame::Lane::Generate, &rid, "send") {
                            let _ = tx2.send(crate::protocol::AgentMsg::Phase(u));
                        }
                    }
                });
                r.map(|n| n).map_err(|e| e.to_string())
            })
            .await;
            match staged {
                Ok(Ok(_)) => {}
                Ok(Err(e)) => anyhow::bail!("stage frames: {e}"),
                Err(e) => anyhow::bail!("stage frames: {e}"),
            }
            if let Some(u) = lanes.snapshot(frame::Lane::Generate, &run_id, "send") {
                let _ = tx.send(crate::protocol::AgentMsg::Phase(u));
            }
        }

        let pacer = {
            let (pl, q, f, s) = (plan.clone(), queue.clone(), frames.clone(), stop.clone());
            std::thread::spawn(move || frame::run_pacer(pl, q, f, s))
        };

        let mut tasks = Vec::new();
        for idx in 0..streams {
            let conn = conns[idx % conns.len()].clone();
            let (pl, q, f, c, l, s) = (
                plan.clone(),
                queue.clone(),
                frames.clone(),
                counters.clone(),
                lanes.clone(),
                stop.clone(),
            );
            tasks.push(tokio::spawn(async move {
                frame_stream_worker(conn, idx, pl, q, f, c, l, s).await;
            }));
        }

        let run_start = Instant::now();
        let (peak, avg, mut rtts) = super::sample_loop_frames(
            run_id.clone(), "send", counters.clone(), stop.clone(), tx.clone(),
            sc.duration_secs, sc.bytes_target, sc.continuous,
            Some(frames.clone()), frame_target, Some(lanes.clone()),
        )
        .await;

        let teardown = Instant::now();
        stop.store(true, Ordering::Relaxed);
        queue.close();
        for t in tasks {
            let _ = t.await;
        }
        let _ = pacer.join();
        for conn in &conns {
            conn.close(0u32.into(), b"done");
        }
        endpoint.wait_idle().await;
        timer.teardown_ms = teardown.elapsed().as_secs_f64() * 1000.0;
        let elapsed = run_start.elapsed().as_secs_f64();
        timer.ramp_ms = 0.0;
        timer.steady_ms = elapsed * 1000.0;
        lanes.finish_all();

        return Ok(timer.finish_frames(
            0, false, &run_id, peak, avg,
            counters.bytes.load(Ordering::Relaxed),
            &mut rtts, &frames, elapsed, &lanes,
        ));
    }

    // One blast task per stream.
    let mut tasks = Vec::new();
    for idx in 0..streams {
        let conn = conns[idx % conns.len()].clone();
        let (p, c, s) = (payload.clone(), counters.clone(), stop.clone());
        tasks.push(tokio::spawn(async move {
            if let Ok(mut send) = conn.open_uni().await {
                while !s.load(Ordering::Relaxed) {
                    match send.write(&p).await {
                        Ok(n) if n > 0 => c.add(idx, n as u64),
                        _ => break,
                    }
                }
                let _ = send.finish();
            }
        }));
    }
    timer.first_byte_ms = first_rtt.load(Ordering::Relaxed) as f64 / 1000.0;

    let (peak, avg, mut rtts) = sample_loop(
        run_id.clone(), "send", counters.clone(), stop.clone(), tx.clone(),
        sc.duration_secs, sc.bytes_target, sc.continuous,
    )
    .await;

    let teardown = Instant::now();
    stop.store(true, Ordering::Relaxed);
    for t in tasks {
        let _ = t.await;
    }
    for conn in &conns {
        conn.close(0u32.into(), b"done");
    }
    endpoint.wait_idle().await;
    timer.teardown_ms = teardown.elapsed().as_secs_f64() * 1000.0;
    timer.ramp_ms = rtts.first().copied().unwrap_or(1.0).clamp(10.0, 1500.0);
    timer.steady_ms = (sc.duration_secs as f64 * 1000.0 - timer.ramp_ms).max(0.0);

    let bytes = counters.bytes.load(Ordering::Relaxed);
    // QUIC handles loss recovery internally; report 0 retransmits, sack N/A.
    Ok(timer.finish(0, false, &run_id, peak, avg, bytes, &mut rtts))
}

/// One frame per uni stream: open, write header + payload, finish. Opening a
/// fresh stream per frame is deliberate — it is what gives each frame an
/// independent delivery path, and it is the behaviour worth comparing against
/// TCP's single ordered byte stream.
async fn frame_stream_worker(
    conn: quinn::Connection,
    idx: usize,
    plan: Arc<frame::FramePlan>,
    queue: Arc<frame::FrameQueue>,
    frames: Arc<frame::FrameCounters>,
    counters: Arc<Counters>,
    lanes: Arc<frame::Lanes>,
    stop: Stop,
) {
    let mut buf = frame::AlignedBuf::new(plan.spec.frame_bytes.max(1) as usize);
    while !stop.load(Ordering::Relaxed) {
        let Some(pos) = queue.take_async().await else {
            break;
        };
        let frame_idx = plan.at(pos);

        let whole = Instant::now();
        let read_from = lanes.now_ns();
        let (returned, res, mut t) = frame::load_frame_async(plan.clone(), frame_idx, buf).await;
        buf = returned;
        let payload = match res {
            Ok(n) => n,
            Err(e) => {
                tracing::warn!("frame {frame_idx}: {e:#}");
                frames.record_drop();
                continue;
            }
        };
        lanes.read.mark(read_from, lanes.now_ns());

        // Opening the stream counts as transmit: on QUIC that is part of what
        // putting a frame on the wire costs, and it is the cost being compared
        // against TCP's single ordered stream.
        let wire_from = lanes.now_ns();
        let Ok(mut send) = conn.open_uni().await else { break };
        let hdr = frame::encode_header(frame_idx, payload as u32, 0);
        if send.write_all(&hdr).await.is_err() {
            break;
        }
        if payload > 0 && send.write_all(&buf.as_slice()[..payload]).await.is_err() {
            break;
        }
        let _ = send.finish();
        let wire_to = lanes.now_ns();
        lanes.transmit.mark(wire_from, wire_to);

        t.wire_ns = wire_to.saturating_sub(wire_from);
        t.total_ns = whole.elapsed().as_nanos() as u64;
        t.bytes = payload as u64;
        frames.record_frame(&t);
        counters.add(idx, (payload + frame::FRAME_HEADER_LEN) as u64);
    }
}

pub async fn run_recv(run_id: String, sc: Scenario, tx: Tx) -> Result<(SocketAddr, Stop)> {
    let ss = crate::tls::self_signed()?;
    let rustls_cfg = crate::tls::server_config(&ss)?;
    let crypto = QuicServerConfig::try_from((*rustls_cfg).clone())?;
    let mut server_cfg = ServerConfig::with_crypto(Arc::new(crypto));
    server_cfg.transport = transport();

    let endpoint = server_endpoint(&sc, server_cfg)?;
    let local = endpoint.local_addr()?;
    let stop: Stop = Arc::new(AtomicBool::new(false));
    // Delivered-goodput accounting: each accepted uni stream claims a slot.
    let counters = Counters::new(sc.threads.max(1) as usize);
    let next_idx = Arc::new(std::sync::atomic::AtomicUsize::new(0));

    // Multi-file: the receiver measures what storing each received frame costs.
    let frame_ctx = if sc.is_multi_file() {
        let plan = frame::validate(&sc)?;
        let total = plan.total();
        Some((
            plan.clone(),
            Arc::new(plan.spec.clone()),
            frame::FrameCounters::new(),
            frame::Lanes::new(total),
        ))
    } else {
        None
    };
    let recv_frames = frame_ctx.as_ref().map(|(_, _, c, _)| c.clone());
    let recv_lanes = frame_ctx.as_ref().map(|(_, _, _, l)| l.clone());
    super::spawn_recv_sampler_frames(run_id, counters.clone(), stop.clone(), tx, recv_frames,
                                     recv_lanes);
    let stop2 = stop.clone();

    tokio::spawn(async move {
        while !stop2.load(Ordering::Relaxed) {
            match tokio::time::timeout(Duration::from_millis(200), endpoint.accept()).await {
                Ok(Some(incoming)) => {
                    let s = stop2.clone();
                    let c = counters.clone();
                    let ni = next_idx.clone();
                    let fc = frame_ctx.clone();
                    tokio::spawn(async move {
                        if let Ok(conn) = incoming.await {
                            drain_conn(conn, s, c, ni, fc).await;
                        }
                    });
                }
                Ok(None) => break,
                Err(_) => {} // timeout tick, re-check stop
            }
        }
        endpoint.close(0u32.into(), b"bye");
    });

    Ok((local, stop))
}

type FrameCtx = (
    Arc<frame::FramePlan>,
    Arc<crate::protocol::FrameSpec>,
    Arc<frame::FrameCounters>,
    Arc<frame::Lanes>,
);

async fn drain_conn(conn: quinn::Connection, stop: Stop, counters: Arc<Counters>,
                    next_idx: Arc<std::sync::atomic::AtomicUsize>,
                    frame_ctx: Option<FrameCtx>) {
    loop {
        if stop.load(Ordering::Relaxed) {
            break;
        }
        match conn.accept_uni().await {
            Ok(mut recv) => {
                let stop = stop.clone();
                let c = counters.clone();
                let idx = next_idx.fetch_add(1, std::sync::atomic::Ordering::Relaxed)
                    % c.streams.len();
                let fc = frame_ctx.clone();
                tokio::spawn(async move {
                    // One frame per stream: read it whole, then store it. The
                    // stream ending *is* the frame boundary, so unlike TCP there
                    // is no need to trust the header's length to reframe.
                    if let Some((plan, spec, frames, lanes)) = fc {
                        recv_frame_stream(recv, plan, spec, frames, c, lanes, idx).await;
                        return;
                    }
                    let mut buf = vec![0u8; CHUNK];
                    while !stop.load(Ordering::Relaxed) {
                        match recv.read(&mut buf).await {
                            Ok(Some(n)) => c.add(idx, n as u64),
                            _ => break,
                        }
                    }
                });
            }
            Err(_) => break,
        }
    }
}

/// Receive one frame off a uni stream and store it, timing the storage work.
async fn recv_frame_stream(
    mut recv: quinn::RecvStream,
    plan: Arc<frame::FramePlan>,
    spec: Arc<crate::protocol::FrameSpec>,
    frames: Arc<frame::FrameCounters>,
    counters: Arc<Counters>,
    lanes: Arc<frame::Lanes>,
    idx: usize,
) {
    let cap = spec.frame_bytes as usize + frame::FRAME_HEADER_LEN;
    let whole = Instant::now();
    let recv_from = lanes.now_ns();
    let Ok(data) = recv.read_to_end(cap).await else {
        return;
    };
    let recv_to = lanes.now_ns();
    lanes.receive.mark(recv_from, recv_to);
    if data.len() < frame::FRAME_HEADER_LEN {
        return;
    }
    let mut hdr = [0u8; frame::FRAME_HEADER_LEN];
    hdr.copy_from_slice(&data[..frame::FRAME_HEADER_LEN]);
    let (seq, _len, _flags) = frame::decode_header(&hdr);
    let payload = &data[frame::FRAME_HEADER_LEN..];

    let mut t = frame::FrameTiming::default();
    t.wire_ns = recv_to.saturating_sub(recv_from);
    if matches!(spec.storage, crate::protocol::FrameStorage::Disk) {
        let write_from = lanes.now_ns();
        let root = if spec.dest_path.is_empty() {
            &spec.path
        } else {
            &spec.dest_path
        };
        let path = plan.path_for(root, seq);
        let (sp, pay) = (spec.clone(), payload.to_vec());
        // Blocking filesystem work off the reactor.
        match tokio::task::spawn_blocking(move || {
            let mut t = frame::FrameTiming::default();
            frame::write_frame_file(&path, &pay, &sp, &mut t).map(|()| t)
        })
        .await
        {
            Ok(Ok(timing)) => {
                // `write_frame_file` builds a fresh timing, so re-attach the
                // receive cost measured above rather than losing it.
                let wire = t.wire_ns;
                t = timing;
                t.wire_ns = wire;
            }
            Ok(Err(e)) => {
                tracing::warn!("receiver frame {seq}: {e:#}");
                frames.record_drop();
                return;
            }
            Err(_) => return,
        }
        lanes.write.mark(write_from, lanes.now_ns());
    }

    t.total_ns = whole.elapsed().as_nanos() as u64;
    t.bytes = payload.len() as u64;
    frames.record_frame(&t);
    counters.add(idx, data.len() as u64);
}

//! TCP data plane, optionally wrapped in TLS 1.3. Single-process sender and receiver;
//! multi-process fan-out lives in `procgroup`.
//!
//! Architectures (the fork the tool measures):
//!  * **Threaded** — one blocking OS thread per connection (`std::net`).
//!  * **Selector** — async tasks on the shared tokio reactor (epoll/kqueue).
//!
//! When `scenario.tls` is set, every connection (blast + probe + receiver) runs over
//! rustls TLS 1.3, so the handshake shows up in the Gantt and throughput reflects
//! encryption cost. Plain and TLS share the worker code via boxed IO traits.

use std::io::{Read, Write};
use std::net::{SocketAddr, TcpStream as StdTcp};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

use anyhow::{Context, Result};
use rustls::pki_types::ServerName;
use socket2::{Domain, Protocol as L4, Socket, Type};
use tokio::io::{AsyncRead, AsyncWrite, AsyncWriteExt};

use super::{frame, sample_loop, tune_tcp, Counters, PhaseTimer, Stop, Tx};
use crate::capabilities::sack_active;
use crate::protocol::Scenario;

const ROLE_BLAST: u8 = b'B';
const ROLE_PROBE: u8 = b'P';
/// Multi-file mode: discrete frames with a per-frame header, rather than an
/// undifferentiated byte stream.
const ROLE_FRAME: u8 = b'F';

// Boxed IO so plain sockets and TLS streams share one worker implementation.
trait SyncIo: Read + Write + Send {}
impl<T: Read + Write + Send> SyncIo for T {}
trait AsyncIo: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T: AsyncRead + AsyncWrite + Unpin + Send> AsyncIo for T {}

fn connect(addr: SocketAddr, sc: &Scenario) -> Result<StdTcp> {
    let sock = Socket::new(Domain::for_address(addr), Type::STREAM, Some(L4::TCP))?;
    tune_tcp(&sock, sc)?;
    sock.connect(&addr.into())
        .with_context(|| format!("connect {addr}"))?;
    Ok(sock.into())
}

/// Wrap a connected blocking stream in a client TLS session if requested.
fn client_sync(std_stream: StdTcp, tls: bool) -> Result<Box<dyn SyncIo>> {
    std_stream.set_nodelay(true).ok();
    if tls {
        let name = ServerName::try_from("localhost")?;
        let conn = rustls::ClientConnection::new(crate::tls::client_config(), name)?;
        Ok(Box::new(rustls::StreamOwned::new(conn, std_stream)))
    } else {
        Ok(Box::new(std_stream))
    }
}

fn blast_thread(mut io: Box<dyn SyncIo>, idx: usize, payload: Arc<Vec<u8>>, c: Arc<Counters>, stop: Stop) {
    let _ = io.write_all(&[ROLE_BLAST]);
    while !stop.load(Ordering::Relaxed) {
        match io.write(&payload) {
            Ok(0) => break,
            Ok(n) => c.add(idx, n as u64),
            Err(ref e) if e.kind() == std::io::ErrorKind::WouldBlock => continue,
            Err(_) => break,
        }
    }
    let _ = io.flush();
}

async fn blast_task(mut io: Box<dyn AsyncIo>, idx: usize, payload: Arc<Vec<u8>>, c: Arc<Counters>, stop: Stop) {
    let _ = io.write_all(&[ROLE_BLAST]).await;
    while !stop.load(Ordering::Relaxed) {
        match io.write(&payload).await {
            Ok(0) => break,
            Ok(n) => c.add(idx, n as u64),
            Err(_) => break,
        }
    }
    let _ = io.shutdown().await;
}

/// Multi-file sender worker: pull paced frames off the queue and push each one
/// down this connection with its header. All the frametest behaviour lives in
/// `engine::frame`; this is only the transport binding.
fn frame_thread(
    mut io: Box<dyn SyncIo>,
    idx: usize,
    plan: Arc<frame::FramePlan>,
    queue: Arc<frame::FrameQueue>,
    frames: Arc<frame::FrameCounters>,
    c: Arc<Counters>,
    lanes: Arc<frame::Lanes>,
    stop: Stop,
) {
    let _ = io.write_all(&[ROLE_FRAME]);
    frame::send_frame_worker(plan, queue, frames, c, lanes, idx, io, stop);
}

fn probe_worker(mut io: Box<dyn SyncIo>, c: Arc<Counters>, stop: Stop, first_rtt: Arc<AtomicU64>) {
    let _ = io.write_all(&[ROLE_PROBE]);
    let mut buf = [0u8; 8];
    let mut first = true;
    while !stop.load(Ordering::Relaxed) {
        let t0 = Instant::now();
        if io.write_all(&[1u8; 8]).is_err() || io.flush().is_err() || io.read_exact(&mut buf).is_err() {
            break;
        }
        let rtt_us = t0.elapsed().as_micros() as u64;
        c.rtt_us.store(rtt_us, Ordering::Relaxed);
        if first {
            first_rtt.store(rtt_us, Ordering::Relaxed);
            first = false;
        }
        std::thread::sleep(Duration::from_millis(250));
    }
}

pub async fn run_send(
    run_id: String,
    sc: Scenario,
    target_addr: String,
    tx: Tx,
    stop: Stop,
) -> Result<crate::protocol::RunSummary> {
    let addr: SocketAddr = super::resolve_connectable(&target_addr)?;
    let threads = sc.threads.max(1) as usize;
    let counters = Counters::new(threads);
    let mut timer = PhaseTimer::start();
    let payload = Arc::new(vec![0xABu8; sc.payload_bytes.max(64) as usize]);
    let first_rtt = Arc::new(AtomicU64::new(0));

    // Probe channel (its TLS handshake is part of the measured setup).
    let probe = client_sync(connect(addr, &sc)?, sc.tls)?;
    timer.handshake_ms = timer.elapsed_ms();
    {
        let (c, s, fr) = (counters.clone(), stop.clone(), first_rtt.clone());
        std::thread::spawn(move || probe_worker(probe, c, s, fr));
    }

    // Multi-file mode replaces the blast loop entirely: a pacer releases frames
    // on the fps deadline and the workers move them one at a time.
    //
    // Frame workers are always OS threads, regardless of the Architecture
    // setting. Per-frame file I/O is blocking on every platform we support, so
    // there is nothing for a reactor to multiplex — claiming otherwise would
    // make the Threaded/Selector comparison meaningless here. The console hides
    // the Architecture control in this mode for the same reason.
    if sc.is_multi_file() {
        let plan = frame::validate(&sc)?;
        frame::apply_pause(&plan.spec);
        let frames = frame::FrameCounters::new();
        let queue = frame::FrameQueue::new(plan.spec.queue_depth as usize);
        let frame_target = plan.total();
        let lanes = frame::Lanes::new(frame_target);

        // Stage the frame set before anything is transmitted, so what follows
        // measures moving frames rather than creating them. Progress streams as
        // it goes: staging a large set takes real time and the console should
        // show it happening, not appear to hang before the run starts.
        // Staging is blocking file I/O and can run for minutes on a large set,
        // so it goes to a blocking thread — same as the QUIC path. Run inline it
        // would hold a tokio worker for the whole phase and stall the telemetry
        // it is trying to report.
        {
            let (pl, ln, st, tx2, rid) =
                (plan.clone(), lanes.clone(), stop.clone(), tx.clone(), run_id.clone());
            let staged = tokio::task::spawn_blocking(move || {
                // `None` until the first emission, so the opening 0/total goes out
                // at once instead of waiting a tick.
                let mut last: Option<Instant> = None;
                let r = frame::pregenerate(&pl, &ln, &st, || {
                    // Throttled to the sampler's cadence; per-frame emission would
                    // flood the control channel on a small-frame run.
                    if last.is_none_or(|t| t.elapsed() >= Duration::from_millis(200)) {
                        last = Some(Instant::now());
                        if let Some(u) = ln.snapshot(frame::Lane::Generate, &rid, "send") {
                            let _ = tx2.send(crate::protocol::AgentMsg::Phase(u));
                        }
                    }
                });
                r.map_err(|e| e.to_string())
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

        let mut workers = Vec::new();
        let connect_start = Instant::now();
        for idx in 0..threads {
            let io = client_sync(connect(addr, &sc)?, sc.tls)?;
            let (pl, q, f, c, l, s) = (
                plan.clone(),
                queue.clone(),
                frames.clone(),
                counters.clone(),
                lanes.clone(),
                stop.clone(),
            );
            workers.push(std::thread::spawn(move || {
                frame_thread(io, idx, pl, q, f, c, l, s)
            }));
        }
        timer.connect_ms = connect_start.elapsed().as_secs_f64() * 1000.0;
        timer.first_byte_ms = first_rtt.load(Ordering::Relaxed) as f64 / 1000.0;

        let pacer = {
            let (pl, q, f, s) = (plan.clone(), queue.clone(), frames.clone(), stop.clone());
            std::thread::spawn(move || frame::run_pacer(pl, q, f, s))
        };

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
        let _ = pacer.join();
        for w in workers {
            let _ = w.join();
        }
        timer.teardown_ms = teardown.elapsed().as_secs_f64() * 1000.0;
        let elapsed = run_start.elapsed().as_secs_f64();
        timer.ramp_ms = 0.0;
        timer.steady_ms = elapsed * 1000.0;
        lanes.finish_all();

        return Ok(timer.finish_frames(
            counters.retransmits.load(Ordering::Relaxed),
            sack_active(),
            &run_id,
            peak,
            avg,
            counters.bytes.load(Ordering::Relaxed),
            &mut rtts,
            &frames,
            elapsed,
            &lanes,
        ));
    }

    // Blast connections.
    let mut os_threads = Vec::new();
    let mut tasks = Vec::new();
    let connect_start = Instant::now();
    let selector = super::is_selector(&sc);
    for idx in 0..threads {
        let std_stream = connect(addr, &sc)?;
        let (p, c, s) = (payload.clone(), counters.clone(), stop.clone());
        if selector {
            std_stream.set_nodelay(true).ok();
            std_stream.set_nonblocking(true).ok();
            let tok = tokio::net::TcpStream::from_std(std_stream)?;
            let io: Box<dyn AsyncIo> = if sc.tls {
                let name = ServerName::try_from("localhost")?;
                let connector = tokio_rustls::TlsConnector::from(crate::tls::client_config());
                Box::new(connector.connect(name, tok).await?)
            } else {
                Box::new(tok)
            };
            tasks.push(tokio::spawn(blast_task(io, idx, p, c, s)));
        } else {
            let io = client_sync(std_stream, sc.tls)?;
            os_threads.push(std::thread::spawn(move || blast_thread(io, idx, p, c, s)));
        }
    }
    timer.connect_ms = connect_start.elapsed().as_secs_f64() * 1000.0;

    for _ in 0..50 {
        if first_rtt.load(Ordering::Relaxed) > 0 {
            break;
        }
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    timer.first_byte_ms = first_rtt.load(Ordering::Relaxed) as f64 / 1000.0;

    let (peak, avg, mut rtts) = sample_loop(
        run_id.clone(), "send", counters.clone(), stop.clone(), tx.clone(),
        sc.duration_secs, sc.bytes_target, sc.continuous,
    )
    .await;

    let teardown = Instant::now();
    stop.store(true, Ordering::Relaxed);
    for h in os_threads {
        let _ = h.join();
    }
    for t in tasks {
        let _ = t.await;
    }
    timer.teardown_ms = teardown.elapsed().as_secs_f64() * 1000.0;
    timer.ramp_ms = rtts.first().copied().unwrap_or(1.0).clamp(20.0, 1500.0) * 8.0;
    timer.steady_ms = (sc.duration_secs as f64 * 1000.0 - timer.ramp_ms).max(0.0);

    let retransmits = counters.retransmits.load(Ordering::Relaxed);
    let bytes = counters.bytes.load(Ordering::Relaxed);
    Ok(timer.finish(retransmits, sack_active(), &run_id, peak, avg, bytes, &mut rtts))
}

pub async fn run_recv(run_id: String, sc: Scenario, tx: Tx) -> Result<(SocketAddr, Stop)> {
    let listener: std::net::TcpListener = super::bind_recv(|bind| {
        let sock = Socket::new(Domain::IPV4, Type::STREAM, Some(L4::TCP))?;
        sock.set_reuse_address(true)?;
        super::apply_dscp(&sock, &sc)?;
        sock.bind(&bind.into())?;
        sock.listen(1024)?;
        Ok(sock.into())
    })?;
    let local = listener.local_addr()?;
    listener.set_nonblocking(false)?;

    // Prebuild the TLS server config once (cert generation is not free).
    let tls_server = if sc.tls {
        let ss = crate::tls::self_signed()?;
        Some(crate::tls::server_config(&ss)?)
    } else {
        None
    };

    let stop: Stop = Arc::new(AtomicBool::new(false));
    // Delivered-goodput accounting: every blast connection claims the next
    // stream slot, and the receiver streams its own "recv" samples up to the console.
    let counters = Counters::new(sc.threads.max(1) as usize);
    let next_idx = Arc::new(std::sync::atomic::AtomicUsize::new(0));

    // In multi-file mode the receiver also measures what *writing* the received
    // frames costs. That is the other half of the Gantt's I/O band — the sender
    // cannot know it, so the receiver reports it on its own telemetry stream.
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
    std::thread::spawn(move || {
        for conn in listener.incoming() {
            if stop2.load(Ordering::Relaxed) {
                break;
            }
            if let Ok(stream) = conn {
                let s = stop2.clone();
                let cfg = tls_server.clone();
                let c = counters.clone();
                let ni = next_idx.clone();
                let fc = frame_ctx.clone();
                std::thread::spawn(move || {
                    stream.set_nodelay(true).ok();
                    let io: Result<Box<dyn SyncIo>> = match cfg {
                        Some(c) => rustls::ServerConnection::new(c)
                            .map(|conn| Box::new(rustls::StreamOwned::new(conn, stream)) as Box<dyn SyncIo>)
                            .map_err(Into::into),
                        None => Ok(Box::new(stream)),
                    };
                    if let Ok(io) = io {
                        recv_conn(io, s, c, ni, fc);
                    }
                });
            } else {
                break;
            }
        }
    });
    Ok((local, stop))
}

type FrameCtx = (
    Arc<frame::FramePlan>,
    Arc<crate::protocol::FrameSpec>,
    Arc<frame::FrameCounters>,
    Arc<frame::Lanes>,
);

fn recv_conn(mut io: Box<dyn SyncIo>, stop: Stop, counters: Arc<Counters>,
             next_idx: Arc<std::sync::atomic::AtomicUsize>, frame_ctx: Option<FrameCtx>) {
    let mut end = [0u8; 1];
    if io.read_exact(&mut end).is_err() {
        return;
    }
    if end[0] == ROLE_FRAME {
        let Some((plan, spec, frames, lanes)) = frame_ctx else {
            // A frame connection arrived but this receiver was prepared for a
            // large-file run — the two ends disagree about the scenario.
            tracing::warn!("frame connection on a receiver not configured for multi-file mode");
            return;
        };
        let idx = next_idx.fetch_add(1, Ordering::Relaxed) % counters.streams.len();
        frame::recv_frame_worker(spec, plan, frames, counters, lanes, idx, io, stop);
        return;
    }
    match end[0] {
        ROLE_PROBE => {
            let mut buf = [0u8; 8];
            while !stop.load(Ordering::Relaxed) {
                if io.read_exact(&mut buf).is_err() || io.write_all(&buf).is_err() || io.flush().is_err() {
                    break;
                }
            }
        }
        _ => {
            // Blast connection: claim a stream slot and count delivered bytes.
            let idx = next_idx.fetch_add(1, Ordering::Relaxed) % counters.streams.len();
            let mut buf = vec![0u8; 256 * 1024];
            while !stop.load(Ordering::Relaxed) {
                match io.read(&mut buf) {
                    Ok(0) | Err(_) => break,
                    Ok(n) => counters.add(idx, n as u64),
                }
            }
        }
    }
}

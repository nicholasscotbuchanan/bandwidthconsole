#!/usr/bin/env python3
"""Headless mock console: drives two bwagents through one real run.

Exercises the entire NDJSON control protocol and the data plane end to end
without JavaFX. Used as the project smoke test:

    ./target/release/bwagent --console 127.0.0.1:9077 --name A &
    ./target/release/bwagent --console 127.0.0.1:9077 --name B &
    python3 tests/mock_console.py

It waits for two agents, runs a TCP/Selector scenario (A -> B), and prints the
per-second telemetry and the final summary. Exit code 0 means a run completed
with non-zero throughput.
"""
import json
import socket
import sys
import threading
import time
import uuid

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 9077

agents = {}         # agentId -> dict(name, caps, conn)
lock = threading.Lock()
receive_ready = {}     # runId -> listenAddr
telemetry = []      # samples
summary = {}        # runId -> summary
done = threading.Event()


def send(conn, obj):
    conn.sendall((json.dumps(obj) + "\n").encode())


def handle(conn):
    f = conn.makefile("r")
    agent_id = None
    for line in f:
        line = line.strip()
        if not line:
            continue
        msg = json.loads(line)
        t = msg.get("type")
        if t == "register":
            agent_id = msg["agentId"]
            with lock:
                agents[agent_id] = {"name": msg["name"], "caps": msg["capabilities"], "conn": conn}
            print(f"[register] {msg['name']} {msg['os']}/{msg['arch']} caps={msg['capabilities']}")
        elif t == "receiveReady":
            receive_ready[msg["runId"]] = msg["listenAddr"]
            print(f"[receiveReady] {msg['runId'][:8]} listen={msg['listenAddr']}")
        elif t == "telemetry":
            s = msg
            telemetry.append(s)
            ps = s.get("perStream", [])
            ps_txt = ("  streams[" + ",".join(f"{v:.0f}" for v in ps[:6])
                      + ("…" if len(ps) > 6 else "") + "]") if ps else ""
            print(f"  t={s['tSecs']:.1f}s  {s['mbps']:.1f} Mbps  rtt={s['rttMs']:.2f}ms  "
                  f"cpu={s.get('cpuPercent', 0):.0f}%{ps_txt}")
        elif t == "runComplete":
            summary[msg["summary"]["runId"]] = msg["summary"]
            print(f"[runComplete] {json.dumps(msg['summary'], indent=2)}")
            done.set()
        elif t == "runError":
            print(f"[runError] {msg['message']}")
            done.set()
        elif t == "heartbeat":
            pass


def main():
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", PORT))
    srv.listen(8)
    print(f"mock console listening on tcp/{PORT}; waiting for 2 agents...")

    def accept_loop():
        while True:
            c, _ = srv.accept()
            threading.Thread(target=handle, args=(c,), daemon=True).start()
    threading.Thread(target=accept_loop, daemon=True).start()

    # Wait for two agents (generous, to tolerate cold container starts).
    import os
    wait_ticks = int(os.environ.get("AGENT_WAIT", "60")) * 20
    for _ in range(wait_ticks):
        with lock:
            if len(agents) >= 2:
                break
        time.sleep(0.05)
    with lock:
        ids = list(agents.keys())
    if len(ids) < 2:
        print("ERROR: need 2 agents")
        sys.exit(2)

    # Ends default to registration order, but can be pinned by agent name --
    # which matters when one side must start first (e.g. the memif server).
    import os as _os
    want_from = _os.environ.get("SC_FROM_NAME")
    want_to = _os.environ.get("SC_TO_NAME")
    from_id, to_id = ids[0], ids[1]
    if want_from or want_to:
        by_name = {a["name"]: aid for aid, a in agents.items()}
        from_id = by_name.get(want_from, from_id)
        to_id = by_name.get(want_to, to_id)
        if from_id == to_id:
            print(f"ERROR: sender and receiver resolved to the same agent")
            sys.exit(6)
    import os
    scenario = {
        "protocol": os.environ.get("SC_PROTO", "Tcp"),
        "architecture": os.environ.get("SC_ARCH", "Selector"),
        "threads": int(os.environ.get("SC_THREADS", "4")),
        "processes": int(os.environ.get("SC_PROCS", "1")),
        "dscp": int(os.environ.get("SC_DSCP", "0")),
        "dscpEnabled": os.environ.get("SC_DSCP_EN", "0") == "1",
        "payloadBytes": int(os.environ.get("SC_PAYLOAD", "32768")),
        "targetMbps": int(os.environ.get("SC_RATE", "0")),
        "durationSecs": int(os.environ.get("SC_DUR", "5")),
        "tls": os.environ.get("SC_TLS", "0") == "1",
        "bytesTarget": int(os.environ.get("SC_BYTES", "0")),
        "continuous": os.environ.get("SC_CONT", "0") == "1",
        "singleConnection": os.environ.get("SC_SINGLECONN", "0") == "1",
        "transferMode": os.environ.get("SC_MODE", "LargeFile"),
    }

    # Multi-file (frametest) mode: discrete frames instead of one stream. The
    # run is bounded by its frame count rather than durationSecs.
    if scenario["transferMode"] == "MultiFile":
        scenario["frame"] = {
            "mode": os.environ.get("SC_FRAME_MODE", "Write"),
            "frameBytes": int(os.environ.get("SC_FRAME_BYTES", str(1024 * 1024))),
            "frameCount": int(os.environ.get("SC_FRAME_COUNT", "200")),
            "fpsLimit": float(os.environ.get("SC_FPS", "0")),
            "queueDepth": int(os.environ.get("SC_QUEUE", "0")),
            "prebuffer": int(os.environ.get("SC_PREBUF", "5")),
            "order": os.environ.get("SC_ORDER", "Sequential"),
            "storage": os.environ.get("SC_STORAGE", "Memory"),
            "path": os.environ.get("SC_FRAME_PATH", ""),
            "destPath": os.environ.get("SC_DEST_PATH", ""),
            "headerKb": int(os.environ.get("SC_HEADER_KB", "64")),
            "directIo": os.environ.get("SC_DIRECT", "1") == "1",
        }
    run_id = str(uuid.uuid4())
    print(f"\n== run {run_id[:8]}  sender={agents[from_id]['name']} -> receiver={agents[to_id]['name']} ==")
    send(agents[to_id]["conn"], {"type": "prepareReceive", "runId": run_id, "scenario": scenario})

    for _ in range(100):
        if run_id in receive_ready:
            break
        time.sleep(0.05)
    if run_id not in receive_ready:
        print("ERROR: receiver never became ready")
        sys.exit(3)

    send(agents[from_id]["conn"], {
        "type": "startSend", "runId": run_id,
        "scenario": scenario, "targetAddr": receive_ready[run_id],
    })

    if not done.wait(timeout=30):
        print("ERROR: run did not complete in time")
        sys.exit(4)

    s = summary.get(run_id)
    if not s or s["avgMbps"] <= 0:
        print("ERROR: zero throughput")
        sys.exit(5)
    print(f"\nOK: avg {s['avgMbps']:.1f} Mbps, peak {s['peakMbps']:.1f} Mbps, "
          f"p95 rtt {s['p95RttMs']:.2f} ms")

    if scenario["transferMode"] == "MultiFile":
        f = s.get("frame")
        if not f:
            print("ERROR: multi-file run returned no frame report")
            sys.exit(7)
        want = scenario["frame"]["frameCount"]
        got, drop = f["framesTransferred"], f["framesDropped"]
        print(f"    frames {got}/{want} transferred, {drop} dropped, "
              f"avg {f['file']['avgMs']:.2f} ms/frame "
              f"(open {f['create']['avgMs']:.3f} / io {f['io']['avgMs']:.3f} "
              f"/ close {f['close']['avgMs']:.3f})")
        if got <= 0:
            print("ERROR: no frames transferred")
            sys.exit(8)
        if drop >= got:
            print("ERROR: dropped at least as many frames as were transferred")
            sys.exit(9)
        if got + drop != want:
            print(f"ERROR: {got}+{drop} frames accounted for, expected {want}")
            sys.exit(10)
        if sum(f["histogram"]) != got:
            print("ERROR: histogram total does not match frames transferred")
            sys.exit(11)
        # The I/O split is what the Gantt's band draws; it must be populated.
        ph = s["phases"]
        print(f"    per frame: {ph['sendIoMs']:.2f} ms sender I/O, "
              f"{ph['netMs']:.2f} ms wire")
        if ph["sendIoMs"] <= 0 and scenario["frame"]["storage"] == "Disk":
            print("ERROR: Disk storage reported no sender I/O time")
            sys.exit(12)
    sys.exit(0)


if __name__ == "__main__":
    main()

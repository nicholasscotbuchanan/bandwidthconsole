# Bandwidth Console

![Live 3D view](docs/Screenshot1.png)

A distributed bandwidth-testing tool that replaces `frametest`. It has two parts:

| Component | Language | Role |
|-----------|----------|------|
| **`bwagent`** | Rust | The *test surface*. One binary that is both client and sink; the console assigns it a role per run. Deploy it in many locations. |
| **Bandwidth Console** | JavaFX | The *control plane and report nexus*. Orchestrates runs across all agents and visualises the results (3D field, live throughput, latency Gantt, scenario comparison). |

The console never carries test traffic. It tells two agents to run a test **directly between themselves** and streams back only telemetry.

```
        ┌──────────────────────────┐
        │     Bandwidth Console     │   control plane (JavaFX)
        │  3D field · Gantt · compare│
        └───────▲────────────▲──────┘
   control (NDJSON/TCP :9077)│            │
        ┌───────┴──────┐   ┌─┴────────────┐
        │  bwagent A   │   │   bwagent B  │
        │  (source)    │──▶│    (sink)    │   data plane, agent-to-agent
        └──────────────┘   └──────────────┘
```

## What the sliders do

Every control maps to real behaviour in the agent's data plane:

| Control | Effect |
|---|---|
| **Protocol** | TCP · TCP+SACK · UDP · UDP+DPDK · **QUIC (TLS 1.3)** · **QUIC+DPDK**. |
| **TLS 1.3** | Encrypts the TCP/SACK path with rustls. QUIC is always encrypted; the toggle greys out elsewhere. |
| **Concurrent streams** | Blast workers: OS threads (Threaded), async tasks (Selector), or QUIC streams. |
| **Concurrent processes** | Real OS-process fan-out: the source spawns N `bwagent worker` children and aggregates their throughput. |
| **Architecture** | **Threaded** = one blocking OS thread per connection · **Selector** = one async reactor (epoll/kqueue) multiplexing all connections. The fork the tool exists to measure. Greyed out for QUIC, which is always async. |
| **Single connection** | QUIC only: multiplex all streams on one connection vs one connection per stream. |
| **Sending** | **Large file** = one continuous stream (the default) · **Multi-file** = thousands of discrete frame files, DVS `frametest` semantics end to end. See below. |
| **Run length** | Duration in seconds, **a payload size to transfer** (1 MB → 10 GB), or **continuous** until stopped. (Multi-file runs are bounded by their frame count instead.) |
| **DSCP** | Sets `IP_TOS = DSCP << 2` on the sockets (best-effort; may be re-marked by intermediate hops). |
| **Offered rate (UDP)** | Token-bucket pacing per stream; 0 = unthrottled. |
| **Fan out** | Run the same scenario on several agents at once: **N sources → 1 sink** (incast — can one server feed six edit bays?) or **N independent pairs** (does it scale when nothing is shared?). |

Measured and reported per run: delivered goodput (aggregate **and per-stream**), source **process CPU%**, packet loss / retransmits, RTT (p50/p95/p99) from a dedicated probe channel (or quinn's path estimate for QUIC), and a six-phase latency breakdown (connect → handshake → first-byte → ramp → steady → teardown) drawn as a Gantt waterfall.

## Multi-file mode — `frametest`, end to end

Real media workloads aren't one big stream; they're thousands of frame files, each
paying open/read/write/close on top of the wire cost. **Multi-file** answers the
question that follows: *how much bandwidth do you actually keep?*

Flip **Sending** to `Multi-file (frames)` and re-run the same scenario. On loopback
the difference is stark:

| Sending | Throughput | Per-frame |
|---|---|---|
| Large file | 86,257 Mbps | — |
| Multi-file, Memory | 7,777 Mbps | 0.73 ms wire, 0.00 ms disk |
| Multi-file, Disk | 7,800 Mbps | 1.38 ms disk, 0.33 ms wire |

**Frame storage** is the second half of the diagnosis. *Disk* reads and writes real
files at both ends (faithful to frametest, measures storage + network together);
*Memory* synthesises frames in RAM and discards them at the sink, isolating the pure
network cost. Run both — if the Gantt's I/O band collapses in Memory mode but
throughput barely moves, your disks were never the problem.

The **Latency Gantt** gains a `PER FRAME` band splitting each frame into
`source disk → network → sink disk`, with a plain verdict underneath
(*"89% of each frame is filesystem I/O — storage-bound"*). The new **Frame I/O**
tab adds the frame-rate-against-deadline chart (shaded red wherever the run fell
short of its target fps), frametest's 13-bucket completion-time histogram, and the
open/transfer/close breakdown with min/avg/max.

**Dropped frames** are what make this a playback test rather than just "lots of
small writes". Frames are paced against the `-f` deadline through a queue of depth
`-q`; a frame that comes due while the queue is full is *dropped*, not delayed, and
excluded from the transferred count — which is why a real frametest run of 1800
frames reports 1796.

**Multi-file needs a reliable transport.** A frame either arrives whole or it did
not arrive, so the UDP paths refuse it with a clear message rather than counting
torn frames. TCP, TCP+SACK and both QUIC variants all work; QUIC maps one frame to
one stream, which is the case its multiplexing was designed for.

### `bwagent frametest` — drop-in CLI

Existing frametest command lines run verbatim, with the original report format:

```bash
bwagent frametest -w 4k -n 3000 -t 4 /mnt/san/TEST
bwagent frametest -r 4k -n 3000 -q 10 -b 5 -f 24 -t 4 /mnt/san/TEST
bwagent frametest -w 1 -z 40000 -n 3000 -t 4 /mnt/san/TEST   # custom size
bwagent frametest -e -n 3000 -t 4 -z 4 /mnt/san/TEST         # metadata/IOPS only
```

Full flag parity, including the ones that are easy to misremember — **`-o` is
out-of-order I/O completion, not an output file** (CSV is `-x`), **`-v` is reverse
order, not verbose** (`--verbose`), and **`-h` is the histogram window, not help**
(the original prints help when given no arguments, and so does this). Attached
forms parse too: `-w12512 -n1800`.

Frame-size presets are full-aperture DPX at 4 bytes/pixel plus a 64 KB header.
`2k` (12,812,288 B) and `4k` (51,052,544 B) are verified byte-exact against the DVS
reference output and the `tframetest` clone. **`sd` and `hd` are inferred** — no
source documents the original's values — and the console says so next to the
control.

Direct I/O (`O_DIRECT` / `F_NOCACHE` / `FILE_FLAG_NO_BUFFERING`) is the default,
inferred from every preset size being an exact 4096 multiple; the original's
buffering mode is undocumented, so `--buffered` is provided to compare.

### Views
**Live 3D** (two real-time 3D graphs: **per-thread contribution** — how much bandwidth each thread is generating, and **goodput vs throughput** — sent-vs-received bars showing whether the sink keeps up with the source; both ends of a run stream telemetry, tagged `source`/`sink`) · **3D Field** (throughput across the streams × processes space) · **Live Throughput** (all runs overlaid) · **Per-stream** (each stream individually — uneven lines expose unfair scheduling the aggregate hides) · **CPU** (where TLS and userspace QUIC cost shows up) · **Latency Gantt** (with the per-frame I/O band) · **Frame I/O** (frame rate vs deadline, completion-time histogram, open/transfer/close split) · **Scenario Comparison** table (fps, frames, drops and worst-frame columns alongside throughput).

**Live Throughput** — every run overlaid on one time axis for direct comparison:

![Live Throughput view](docs/Screenshot2.png)

**Per-stream** — each stream's goodput stacked individually; uneven bands expose unfair scheduling the aggregate hides:

![Per-stream view](docs/Screenshot3.png)

**CPU** — source (solid) and sink (dashed) process CPU per run; this is where TLS and userspace QUIC cost shows up:

![CPU view](docs/Screenshot4.png)

**Latency Gantt** — the six-phase breakdown (connect → handshake → first-byte → ramp → steady → teardown) with RTT percentiles:

![Latency Gantt view](docs/Screenshot5.png)

### Capability gating
Each agent probes its host at startup and reports capabilities (`dpdk`, `dscp`, `sack`, cpu count). The console **greys out** controls the *selected source+sink pair* can't honour — e.g. UDP+DPDK is unavailable unless both agents report DPDK, so you can't launch an impossible scenario.

## Protocols and honesty about DPDK

- **TCP / TCP+SACK / UDP** are fully implemented and verified. UDP uses lightweight receiver feedback so it reports *delivered* throughput and real loss, not just offered load. SACK is a kernel attribute (`net.ipv4.tcp_sack`); the agent reports whether it is actually active rather than pretending to toggle it per-socket.
- **QUIC** is fully implemented via quinn: TLS 1.3, real streams, quinn's own path-RTT estimate, and a choice of multiplexing all streams on one connection or one connection per stream.

### The two DPDK datapaths

**UDP+DPDK** blasts datagrams straight over a poll-mode driver. **QUIC+DPDK** runs the *same* quinn QUIC stack — TLS 1.3, streams, loss recovery — but swaps its kernel UDP transport for DPDK. quinn abstracts that transport behind its `AsyncUdpSocket` trait, so [`DpdkUdpSocket`](agent/src/engine/dpdk.rs) is the entire integration point: implement it and QUIC runs kernel-bypass unchanged.

Both are **Linux-only** and gated behind the `dpdk` cargo feature. The datapath is fully implemented:

| Piece | Where |
|---|---|
| EAL init, mbuf pool, port/queue setup, `rte_eth_rx_burst`/`tx_burst` | [`agent/dpdk/shim.c`](agent/dpdk/shim.c) |
| Userspace Ethernet + IPv4 (real header checksum) + UDP framing, rx demux, poll thread | [`agent/src/dpdkrt.rs`](agent/src/dpdkrt.rs) |
| `DpdkUdpSocket` (quinn's `AsyncUdpSocket`) + raw UDP engine | [`agent/src/engine/dpdk.rs`](agent/src/engine/dpdk.rs) |
| Compiling the shim, linking libdpdk | [`agent/build.rs`](agent/build.rs) |

A **C shim is mandatory, not a shortcut**: DPDK's hot path (`rte_eth_rx_burst`, `rte_pktmbuf_alloc`, …) is `static inline` in the headers, so those symbols exist in no library and cannot be called from Rust FFI. Each one has to be wrapped in a real linkable function.

Because DPDK delivers raw Ethernet frames, the agent carries its own L2/L3/L4. Two simplifications, both honest for a point-to-point bench link: frames go to the **broadcast MAC** (no ARP needed — the link has exactly one other end and the port is promiscuous), and the **UDP checksum is 0** (explicitly legal for IPv4). The **IPv4 header checksum is computed properly** and unit-tested.

#### Running it without a NIC

You do **not** need hugepages, a spare NIC, or vfio binding. DPDK's `net_memif` shared-memory PMD gives a real DPDK-to-DPDK link between two containers:

```bash
podman build -t bwagent-dpdk -f agent/Dockerfile.dpdk agent
agent/tests/dpdk-test.sh QuicDpdk     # or UdpDpdk
```

or via compose: `docker compose --profile dpdk up --build`. One agent is the memif server, the other the client, sharing the socket through a volume; EAL runs with `--no-huge`. Each agent gets its own IP on the DPDK link (`--dpdk-ip`), and for DPDK runs the sink advertises *that* address rather than its control-plane one.

#### Jumbo frames

The DPDK link runs a **9000-byte MTU**, not the 1500 default — on a point-to-point bench link there is no reason to pay per-packet overhead six times over. Three things have to agree, or frames get silently truncated:

| Setting | Where |
|---|---|
| `rxmode.mtu = 9000`, clamped to the PMD's `max_mtu` | [`agent/dpdk/shim.c`](agent/dpdk/shim.c) |
| Mbufs sized to hold a whole jumbo frame in **one segment** (no chaining) | [`agent/dpdk/shim.c`](agent/dpdk/shim.c) |
| `MAX_FRAME` / `MAX_PAYLOAD` | [`agent/src/dpdkrt.rs`](agent/src/dpdkrt.rs) |
| memif `bsize=16384` on both vdevs | [`docker-compose.yml`](docker-compose.yml) |

The clamp matters: a PMD that cannot do 9000 gets its own maximum rather than a failed `rte_eth_dev_configure()`, so the agent still starts.

Where DPDK genuinely isn't available, both protocols **fail loudly** with the specific reason ("Linux-only" vs "built without the `dpdk` feature" vs "not configured"), and the console greys them out unless *both* agents report the capability. No fabricated DPDK numbers ever reach a graph.

#### Measured over the memif link (aarch64, 2 streams, 5 s)

| Protocol | Avg | Peak | p50 RTT |
|---|---|---|---|
| UDP+DPDK | 6.9 Gbps | 11.6 Gbps | 1.95 ms |
| QUIC+DPDK | 8.4 Gbps | 9.4 Gbps | 0.27 ms |
| QUIC over kernel UDP (for comparison) | ~2.9 Gbps | — | — |

The ~3× gap between QUIC-over-kernel and QUIC-over-DPDK is the kernel-bypass win,
and it is exactly the kind of question this tool exists to answer. (memif is a
shared-memory link, so absolute numbers reflect this host, not a NIC.)

#### Gotchas worth knowing

- **`socket-abstract=no` is mandatory across containers.** memif defaults to an
  *abstract* unix socket, which lives in the network namespace rather than the
  filesystem — no file appears on the shared volume and two containers can never
  rendezvous. The server looks healthy while the client logs
  `memif_connect_client(): Failed to connect socket`.
- **A stale `memif.sock` blocks startup** with `Address already in use`; the test
  script removes it before each run.
- **EAL initialises once per process.** The agent brings the datapath up eagerly
  at startup *and* lazily per run, so bring-up is behind a mutex — otherwise the
  losing thread's `rte_eal_init` returns -2.
- **Process CPU on Linux is read from `/proc/self/stat`,** not sysinfo, which
  reported a flat 0% inside containers. macOS still uses sysinfo.

## Build & run

### Prerequisites
- Rust (stable) and Cargo — for the agent.
- JDK 17+ with `jpackage`, and Maven — for the console. `mvn javafx:run` fetches JavaFX automatically.

### Quick local demo
```bash
./run-demo.sh
```
Builds the agent, opens the console, and brings up **four nodes**:

| Node | Kind | Datapath |
|---|---|---|
| `edge-a`, `edge-b` | local host processes | kernel |
| `dpdk-src`, `dpdk-sink` | Linux containers | DPDK + [jumbo frames](#jumbo-frames) |

Pick `edge-a` → `edge-b` for the kernel path, set the sliders, and hit **Run**. **Add to queue** + **Run queue** runs several scenarios back-to-back for comparison.

The two containers are linked to each other by DPDK's `net_memif` PMD and report the `dpdk` capability, so selecting `dpdk-src` → `dpdk-sink` unlocks **UDP+DPDK** and **QUIC+DPDK** — greyed out on any pair that doesn't have it. The container half needs docker or podman; without either, the demo still runs the two local agents. First build takes a few minutes.

For more nodes, `docker-compose.yml` also defines three kernel-datapath containers: `docker compose up -d edge1 edge2 edge3`.

### Agent, by hand
```bash
cd agent && cargo build --release
./target/release/bwagent --console <console-host>:9077 --name edge-nyc --advertise <this-host-ip>
```
`--advertise` is the address other agents use to reach this one's data plane (its routable IP). Real DPDK build: `packaging/build-agent.sh --dpdk` (Linux).

### Console, by hand
```bash
cd console && mvn javafx:run
```

### Agents in Docker (three of them)

`docker-compose.yml` runs three containerised agents (`edge1`, `edge2`, `edge3`)
on a shared bridge network. Start the console on your host first, then:

```bash
(cd console && mvn javafx:run)     # host — binds 0.0.0.0:9077
docker compose up --build          # three agent containers
```

Each container dials the console via `host.docker.internal:9077`. The data plane
runs container-to-container over the `bwnet` network: every agent advertises its
**service name** (`edge1`…), so when the console pairs e.g. `edge1 → edge2`,
`edge1` connects straight to the host `edge2` over `bwnet`. Because the target is
a hostname, the agent resolves it through DNS (not a literal-IP parse).

Containers run Linux, so they report `dscp`/`sack` true and read the real
`net.ipv4.tcp_sack`; `dpdk` stays false (greyed out) unless built with the
feature on a DPDK-provisioned host. Podman works too — the image builds from the
same Dockerfile and container-name DNS resolves on a user-defined network.

Scale beyond three by copying an `edge*` service block, or point agents on other
hosts at the console's real IP with `--console <host>:9077 --advertise <this-ip>`.

## Persistence and Past Runs replay (InfluxDB)

Optional. Point the console at an InfluxDB v2 instance and every sample and run
summary is persisted; the **Past Runs** picker then reloads a stored run into the
live views so you can compare it against what's running now.

```bash
export BW_INFLUX_URL=http://localhost:8086
export BW_INFLUX_TOKEN=bwtest-token
export BW_INFLUX_ORG=bwtest
export BW_INFLUX_BUCKET=bwtest
cd console && mvn javafx:run
```

`docker compose up influxdb` brings up a preconfigured instance with those
credentials. With `BW_INFLUX_URL` unset the client is inert and the console runs
normally — nothing else changes.

Two measurements are written: `bw_sample` (per-sample goodput/rtt/cpu, tagged by
runId/protocol/arch/source/sink) and `bw_run` (the summary + latency phases).
Per-stream detail is *not* persisted, so a replayed run has an empty per-stream
chart. `InfluxSmoke` round-trips the write and query paths:

```bash
java -cp target/classes:$(cat /tmp/cp.txt) com.bwtest.console.net.InfluxSmoke
```

## Packaging (multi-platform release)

- **Agent:** `packaging/build-agent.sh` (native) or `TARGET=<triple> packaging/build-agent.sh` to cross-compile. Per-OS release binaries come from a CI matrix or `cross` (the Homebrew dev Rust only has the host target).
- **Console:** `packaging/build-console.sh` produces a native installer via `jpackage` — `.dmg` on macOS, `.msi` on Windows, `.deb` on Linux. Run it on each OS with `JAVAFX_JMODS` pointing at that platform's JavaFX jmods bundle (from https://openjfx.io).

## Verifying

The Rust data plane and both wire directions are covered by a headless smoke test:
```bash
cd agent
python3 tests/mock_console.py 9077 &            # stand-in console
./target/release/bwagent --console 127.0.0.1:9077 --name A &
./target/release/bwagent --console 127.0.0.1:9077 --name B &
# prints per-second telemetry and the final summary; exit 0 = healthy run
```
Set `SC_PROTO`, `SC_ARCH`, `SC_THREADS`, `SC_PROCS`, `SC_DSCP`/`SC_DSCP_EN`, `SC_RATE`, `SC_DUR` to exercise other scenarios.

**Multi-file** runs are covered by the same harness. `SC_MODE=MultiFile` switches
it on, and the smoke test then also asserts that every frame is accounted for
(transferred + dropped == requested), that the histogram totals match, and that the
Gantt's I/O split is populated:

```bash
SC_MODE=MultiFile SC_STORAGE=Disk SC_FRAME_MODE=Write \
  SC_FRAME_BYTES=1048576 SC_FRAME_COUNT=200 SC_THREADS=4 \
  SC_FRAME_PATH=/tmp/frames-src SC_SINK_PATH=/tmp/frames-dst \
  python3 tests/mock_console.py 9077
```
Also honoured: `SC_FPS`, `SC_QUEUE`, `SC_PREBUF`, `SC_ORDER`, `SC_HEADER_KB`,
`SC_DIRECT`. Use `SC_STORAGE=Memory` for the no-disk comparison.

The frame engine's own behaviour — byte-exact preset sizes, histogram bucketing,
drop accounting, filename patterns, direct-I/O alignment — and the frametest CLI's
flag grammar are unit tested: `cd agent && cargo test` (39 tests).

The **real DPDK datapath** has its own end-to-end test — two agents linked by
the `net_memif` shared-memory PMD, no NIC and no hugepages required:

```bash
podman build -t bwagent-dpdk -f agent/Dockerfile.dpdk agent
agent/tests/dpdk-test.sh UdpDpdk      # or QuicDpdk
```

The userspace framing (IPv4 checksum, roundtrip, non-UDP rejection) is unit
tested: `cd agent && cargo test`.

### Looking at the UI without running a full stack

`UiSnapshot` renders the real console with representative agents and runs seeded,
and writes a PNG per tab to `/tmp/ui-*.png`:

```bash
cd console
mvn -o dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt
CP=$(cat /tmp/cp.txt); FX=$(echo "$CP" | tr ':' '\n' | grep -E 'javafx-(base|graphics|controls)' | tr '\n' ':')
java --module-path "$FX" --add-modules javafx.controls,javafx.graphics \
     -cp "target/classes:$CP" com.bwtest.console.ui.UiSnapshot
```

Use it when changing anything visual. The layout lives in a single class
(`ConsoleUI`) precisely so the app and this harness always render the same thing.

The console's own networking/JSON layer has a JavaFX-free check in
[`HeadlessSmoke`](console/src/main/java/com/bwtest/console/net/HeadlessSmoke.java) that drives a real run using the production `ControlServer`/`AgentConnection`.

## Control protocol

NDJSON over TCP. Agents dial the console (so they work behind NAT). Messages are JSON objects tagged by `"type"`; the Rust `protocol.rs` and the Java `AgentConnection` mirror each other. Agent→console: `register`, `heartbeat`, `roleReady`, `telemetry`, `runComplete`, `runError`, `log`. Console→agent: `prepareSink`, `startSource`, `abort`.

## Layout
```
agent/              Rust bwagent (control client + data-plane engines + process fan-out)
agent/Dockerfile    container image for the agent
console/            JavaFX console (control server, orchestrator, UI)
packaging/          build scripts for agent binaries and console installers
docker-compose.yml  three containerised agents on a shared network
```

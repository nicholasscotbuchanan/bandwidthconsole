#!/usr/bin/env bash
# End-to-end test of the REAL DPDK datapath (UDP+DPDK and QUIC+DPDK).
#
# Topology: two bwagent containers linked by DPDK's net_memif shared-memory PMD
# — a genuine DPDK-to-DPDK link that needs no NIC, no hugepages and no vfio
# binding. One side is the memif server, the other the client; they share the
# memif unix socket through a volume. A third container runs the mock console
# and orchestrates one run.
#
# NOTE: socket-abstract=no is essential. memif defaults to an *abstract* unix
# socket, which lives in the network namespace rather than the filesystem — so
# nothing appears on the shared volume and two containers (separate netns) can
# never rendezvous. Forcing a real socket file makes the volume the meeting point.
#
#   agent/tests/dpdk-test.sh [Quic Dpdk|UdpDpdk]   (default QuicDpdk)
#
# Requires the DPDK image:  podman build -t bwagent-dpdk -f agent/Dockerfile.dpdk agent
set -uo pipefail
export PATH=$PATH:/opt/podman/bin
CT=${CT:-podman}
PROTO=${1:-QuicDpdk}
DUR=${DUR:-5}
THREADS=${THREADS:-2}

SINK_IP=10.99.0.2
SRC_IP=10.99.0.1
SOCK_DIR=/run/memif

cleanup() { $CT rm -f bw-dpdk-console bw-dpdk-sink bw-dpdk-src >/dev/null 2>&1; }
trap cleanup EXIT
cleanup
$CT network create bwnet >/dev/null 2>&1
$CT volume create memifsock >/dev/null 2>&1
# A unix socket file left by a previous run makes memif_socket_create() fail with
# "Address already in use" -- the server cannot rebind over a stale inode.
$CT run --rm -v memifsock:$SOCK_DIR --entrypoint sh bwagent-dpdk \
  -c "rm -f $SOCK_DIR/memif.sock" >/dev/null 2>&1

echo "== console =="
$CT run -d --name bw-dpdk-console --network bwnet \
  -e SC_PROTO="$PROTO" -e SC_DUR="$DUR" -e SC_THREADS="$THREADS" -e AGENT_WAIT=90 \
  -e SC_PAYLOAD=1200 -e SC_SINGLECONN=1 \
  -e SC_SOURCE_NAME=dpdk-src -e SC_SINK_NAME=dpdk-sink \
  -w /t -v "$(cd "$(dirname "$0")" && pwd):/t:ro" \
  python:3-slim python3 /t/mock_console.py 9077 >/dev/null
for i in $(seq 1 20); do $CT logs bw-dpdk-console 2>&1 | grep -q listening && break; sleep 1; done

# Roles are pinned by name (SC_SOURCE_NAME/SC_SINK_NAME), so start order is free
# for the console -- but the memif SERVER must exist before the client dials it,
# so the sink goes first. (The client also retries, so this is belt-and-braces.)
echo "== sink agent (memif server, $SINK_IP) =="
$CT run -d --name bw-dpdk-sink --network bwnet --privileged -v memifsock:$SOCK_DIR \
  bwagent-dpdk --console bw-dpdk-console:9077 --name dpdk-sink --advertise bw-dpdk-sink \
  --dpdk-ip $SINK_IP \
  --dpdk-eal "bwagent -l 1 --no-huge -m 256 --no-pci --file-prefix=sink --vdev=net_memif0,role=server,id=0,bsize=16384,socket=$SOCK_DIR/memif.sock,socket-abstract=no" >/dev/null
sleep 3

echo "== source agent (memif client, $SRC_IP) =="
$CT run -d --name bw-dpdk-src --network bwnet --privileged -v memifsock:$SOCK_DIR \
  bwagent-dpdk --console bw-dpdk-console:9077 --name dpdk-src --advertise bw-dpdk-src \
  --dpdk-ip $SRC_IP \
  --dpdk-eal "bwagent -l 0 --no-huge -m 256 --no-pci --file-prefix=src --vdev=net_memif0,role=client,id=0,bsize=16384,socket=$SOCK_DIR/memif.sock,socket-abstract=no" >/dev/null

echo "== waiting for run =="
for i in $(seq 1 90); do
  $CT logs bw-dpdk-console 2>&1 | grep -qE "OK:|ERROR:|runError" && break
  sleep 1
done

echo
echo "=========== CONSOLE ==========="
$CT logs bw-dpdk-console 2>&1 | tail -25
echo
echo "=========== SOURCE AGENT ==========="
$CT logs bw-dpdk-src 2>&1 | grep -viE "^EAL: |TELEMETRY" | tail -12
echo
echo "=========== SINK AGENT ==========="
$CT logs bw-dpdk-sink 2>&1 | grep -viE "^EAL: |TELEMETRY" | tail -12

$CT logs bw-dpdk-console 2>&1 | grep -q "OK:" && { echo; echo "RESULT: PASS ($PROTO over real DPDK)"; exit 0; }
echo; echo "RESULT: FAIL"; exit 1

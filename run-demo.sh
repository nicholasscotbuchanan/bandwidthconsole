#!/usr/bin/env bash
# Local end-to-end demo: build the agent, launch the JavaFX console, and bring
# up a fleet of agents that dial into it. Then configure a scenario and Run.
#
#   ./run-demo.sh
#
# Four nodes register with the console:
#
#   edge-a, edge-b          local processes on the host (127.0.0.1)
#   dpdk-a, dpdk-b     Linux containers, DPDK datapath + jumbo frames
#
# The DPDK pair is linked to each other by DPDK's net_memif shared-memory PMD —
# a genuine DPDK-to-DPDK datapath needing no NIC, no hugepages and no vfio
# binding — and is the only pair that can run UDP+DPDK and QUIC+DPDK.
#
# (docker-compose.yml also defines edge1/edge2/edge3 kernel-datapath containers
# if you want more nodes: `docker compose up -d edge1 edge2 edge3`.)
set -euo pipefail
cd "$(dirname "$0")"

# Any OCI runtime works — they share a CLI surface, so the only question is which
# one is installed. Probed rather than assumed, and overridable with
# COMPOSE="podman compose" ./run-demo.sh when a host has more than one.
#
# Containers are a bonus, not a hard dependency: without a runtime the two local
# agents still give a working (if smaller) demo rather than an aborted one.
export PATH=$PATH:/opt/podman/bin
COMPOSE="${COMPOSE:-}"
if [[ -z "$COMPOSE" ]]; then
  # podman-first, matching the rest of the repo (agent/tests/dpdk-test.sh).
  if podman compose version >/dev/null 2>&1; then
    COMPOSE="podman compose"      # podman delegates to whatever compose it finds
  elif podman-compose version >/dev/null 2>&1; then
    COMPOSE="podman-compose"
  elif docker info >/dev/null 2>&1; then
    COMPOSE="docker compose"
  elif nerdctl compose version >/dev/null 2>&1; then
    COMPOSE="nerdctl compose"
  else
    echo "!! no podman/docker/nerdctl compose found — starting the 2 local agents only." >&2
  fi
fi
[[ -n "$COMPOSE" ]] && echo ">> container runtime: $COMPOSE"

echo ">> building agent (release)"
( cd agent && cargo build --release )
BIN=agent/target/release/bwagent

cleanup() {
  pkill -f "bwagent --console" 2>/dev/null || true
  # Only what this script started — a bare `down` would also take out influxdb
  # and any edge1-3 the user brought up separately.
  [[ -n "$COMPOSE" ]] && $COMPOSE rm -sf dpdk-b dpdk-a 2>/dev/null || true
}
trap cleanup EXIT

# Compile as its own announced step. `mvn javafx:run` otherwise resolves
# dependencies and compiles *before* the JVM reaches App.start(), so the loader
# window — whose whole job is to say "something is happening" — cannot paint
# until the slowest part is already over. Doing it here costs the same seconds
# but attributes them, and leaves the launch below with nothing to do but start.
echo ">> building console"
( cd console && mvn -q -o compile 2>/dev/null || mvn -q compile )

echo ">> starting console (JavaFX window)"
( cd console && mvn -q javafx:run ) &
CONSOLE=$!

# Give the console a moment to bind its control port.
for i in $(seq 1 30); do
  nc -z localhost 9077 2>/dev/null && break
  sleep 1
done

echo ">> starting 2 local agents"
"$BIN" --console 127.0.0.1:9077 --name "edge-a" --advertise 127.0.0.1 &
"$BIN" --console 127.0.0.1:9077 --name "edge-b" --advertise 127.0.0.1 &

if [[ -n "$COMPOSE" ]]; then
  echo ">> starting 2 DPDK containers ($COMPOSE)"
  echo "   (first run builds the DPDK image — takes a few minutes)"
  $COMPOSE up --build -d dpdk-b dpdk-a
fi

echo ">> ready. Pick any two agents — either can send to the other:"
echo "   kernel datapath  edge-a -> edge-b"
if [[ -n "$COMPOSE" ]]; then
  echo "   kernel bypass    dpdk-a -> dpdk-b, protocol UDP+DPDK or QUIC+DPDK"
fi
wait $CONSOLE

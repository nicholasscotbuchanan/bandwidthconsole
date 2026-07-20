#!/usr/bin/env bash
# Build the bwagent test-surface binary.
#
# Native build (this host):        packaging/build-agent.sh
# With real DPDK (Linux only):     packaging/build-agent.sh --dpdk
# Cross-compile a target:          TARGET=x86_64-unknown-linux-gnu packaging/build-agent.sh
#
# Cross-compilation needs rustup targets (or `cross`). The Homebrew rust used in
# dev only has the host target, so per-OS release binaries are produced by CI
# runners (one per OS) or `cross`:
#
#   rustup target add x86_64-unknown-linux-gnu aarch64-apple-darwin x86_64-pc-windows-gnu
#   cargo install cross    # or use CI matrix
#
set -euo pipefail
cd "$(dirname "$0")/../agent"

FEATURES=""
if [[ "${1:-}" == "--dpdk" ]]; then
  # DPDK is Linux-only and needs the SDK + hugepages + a vfio-bound NIC at runtime.
  [[ "$(uname -s)" == "Linux" ]] || { echo "DPDK feature is Linux-only"; exit 1; }
  FEATURES="--features dpdk"
  echo ">> building with real DPDK data path (requires DPDK SDK on PATH)"
fi

if [[ -n "${TARGET:-}" ]]; then
  echo ">> cross-compiling for $TARGET"
  if command -v cross >/dev/null 2>&1; then
    cross build --release --target "$TARGET" $FEATURES
  else
    cargo build --release --target "$TARGET" $FEATURES
  fi
  echo ">> binary: target/$TARGET/release/bwagent"
else
  cargo build --release $FEATURES
  echo ">> binary: target/release/bwagent"
fi

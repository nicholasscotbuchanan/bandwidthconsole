# Multi-stage build for the bwagent test surface.
#
#   docker build -t bwagent -f agent/Dockerfile agent
#
# The runtime image is Linux, so containerised agents report dscp=true and read
# the real net.ipv4.tcp_sack. DPDK stays off unless you build with the feature
# on a DPDK-provisioned host (see packaging/build-agent.sh --dpdk).

FROM rust:1-slim-bookworm AS build
WORKDIR /src
# Cache dependencies first.
COPY Cargo.toml Cargo.lock ./
RUN mkdir src && echo "fn main() {}" > src/main.rs && \
    cargo build --release && rm -rf src
# Now the real sources.
COPY src ./src
RUN touch src/main.rs && cargo build --release

FROM debian:bookworm-slim AS runtime
# ca-certificates only; the agent is otherwise self-contained.
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /src/target/release/bwagent /usr/local/bin/bwagent
# The control connection is outbound; the data plane binds ephemeral ports that
# peer agents reach over the shared compose network.
ENTRYPOINT ["/usr/local/bin/bwagent"]

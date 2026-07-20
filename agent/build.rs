//! Compiles the DPDK C shim and links libdpdk — only when the `dpdk` feature is
//! on. Without the feature this is a no-op (and `cc`/`pkg-config` aren't even
//! dependencies), so the agent still builds anywhere: macOS, Windows, or a Linux
//! host with no DPDK SDK installed.

fn main() {
    println!("cargo:rerun-if-changed=dpdk/shim.c");
    println!("cargo:rerun-if-changed=build.rs");

    #[cfg(feature = "dpdk")]
    build_dpdk();
}

#[cfg(feature = "dpdk")]
fn build_dpdk() {
    // Pass 1: query DPDK for include paths WITHOUT emitting any cargo metadata.
    let probe = pkg_config::Config::new()
        .cargo_metadata(false)
        .probe("libdpdk")
        .expect(
            "the `dpdk` feature requires the DPDK SDK: install libdpdk-dev \
             (Debian/Ubuntu) or dpdk-devel (RHEL) so pkg-config can find libdpdk",
        );

    let mut build = cc::Build::new();
    build.file("dpdk/shim.c").opt_level(3);
    for p in &probe.include_paths {
        build.include(p);
    }
    // Emits `-lbwdpdkshim`.
    build.compile("bwdpdkshim");

    // Pass 2: NOW emit DPDK's link flags, so they land *after* the shim on the
    // link line. GNU ld resolves left to right: a static library only pulls in
    // symbols for references it has already seen, so libdpdk must follow the
    // shim that needs it. Emitting them the other way round produces undefined
    // references to rte_eth_fp_ops, per_lcore__lcore_id, and friends.
    pkg_config::Config::new()
        .probe("libdpdk")
        .expect("libdpdk pkg-config");

    // DPDK's .pc uses --as-needed, which can drop driver libs whose symbols are
    // only referenced via constructors (that's how PMDs register themselves).
    println!("cargo:rustc-link-arg=-Wl,--no-as-needed");
}

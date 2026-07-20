package com.bwtest.console.model;

/**
 * Wire protocols selectable in the control panel. {@code wire} is the exact
 * string the Rust agent's serde enum expects; {@code label} is what the user
 * sees. {@code requiresDpdk}/{@code requiresSack} drive control greying based on
 * the selected agents' reported capabilities.
 */
public enum Protocol {
    TCP("Tcp", "TCP", false, false),
    TCP_SACK("TcpSack", "TCP + SACK", false, true),
    UDP("Udp", "UDP", false, false),
    UDP_DPDK("UdpDpdk", "UDP + DPDK", true, false),
    QUIC("Quic", "QUIC (TLS 1.3)", false, false),
    /** The same quinn QUIC stack, but its UDP transport is a DPDK poll-mode
     *  driver instead of kernel sockets. Needs the dpdk capability on both ends. */
    QUIC_DPDK("QuicDpdk", "QUIC + DPDK (TLS 1.3)", true, false);

    /** QUIC is always encrypted and always async, so the TLS toggle and the
     *  Threaded/Selector choice don't apply to either QUIC variant. */
    public boolean isQuic() {
        return this == QUIC || this == QUIC_DPDK;
    }

    /** Protocols where the TLS 1.3 toggle is meaningful (TCP family). */
    public boolean supportsTlsToggle() {
        return this == TCP || this == TCP_SACK;
    }

    public final String wire;
    public final String label;
    public final boolean requiresDpdk;
    public final boolean requiresSack;

    Protocol(String wire, String label, boolean requiresDpdk, boolean requiresSack) {
        this.wire = wire;
        this.label = label;
        this.requiresDpdk = requiresDpdk;
        this.requiresSack = requiresSack;
    }

    @Override
    public String toString() {
        return label;
    }
}

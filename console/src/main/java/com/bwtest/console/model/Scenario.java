package com.bwtest.console.model;

/**
 * A test configuration — exactly what the sliders produce. Field names are
 * camelCase to match the agent's serde {@code rename_all = "camelCase"} so
 * Jackson serialises straight onto the wire.
 */
public class Scenario {
    public String protocol;      // Protocol.wire
    public String architecture;  // Architecture.wire
    public int threads;
    public int processes;
    public int dscp;
    public boolean dscpEnabled;
    public int payloadBytes;
    public int targetMbps;
    public int durationSecs;
    /** TLS 1.3 on the TCP path (QUIC is always encrypted). */
    public boolean tls;
    /** Total bytes to move before stopping; 0 = run for durationSecs. */
    public long bytesTarget;
    /** Run until stopped, ignoring duration and byte target. */
    public boolean continuous;
    /** QUIC: all streams on one connection vs one connection per stream. */
    public boolean singleConnection;
    /** One continuous stream (default) or discrete frames. */
    public String transferMode = TransferMode.LARGE_FILE.wire;
    /** frametest parameters; null unless {@code transferMode} is multi-file. */
    public FrameSpec frame;

    public Scenario() {}

    public static Scenario of(Protocol p, Architecture a, int threads, int processes,
                              int dscp, boolean dscpEnabled, int payloadBytes,
                              int targetMbps, int durationSecs) {
        Scenario s = new Scenario();
        s.protocol = p.wire;
        s.architecture = a.wire;
        s.threads = threads;
        s.processes = processes;
        s.dscp = dscp;
        s.dscpEnabled = dscpEnabled;
        s.payloadBytes = payloadBytes;
        s.targetMbps = targetMbps;
        s.durationSecs = durationSecs;
        return s;
    }

    /** Fluent extras so callers only set what they care about. */
    public Scenario withTls(boolean v) { this.tls = v; return this; }
    public Scenario withBytesTarget(long v) { this.bytesTarget = v; return this; }
    public Scenario withContinuous(boolean v) { this.continuous = v; return this; }
    public Scenario withSingleConnection(boolean v) { this.singleConnection = v; return this; }

    /** Switch to multi-file (frametest) mode with the given frame parameters. */
    public Scenario withFrames(FrameSpec f) {
        this.transferMode = TransferMode.MULTI_FILE.wire;
        this.frame = f;
        return this;
    }

    /** Whether this run is bounded by bytes rather than time. */
    public boolean isByteDriven() {
        return !continuous && bytesTarget > 0;
    }

    public boolean isMultiFile() {
        return TransferMode.MULTI_FILE.wire.equals(transferMode) && frame != null;
    }

    public TransferMode transferModeEnum() {
        for (TransferMode m : TransferMode.values()) {
            if (m.wire.equals(transferMode)) return m;
        }
        return TransferMode.LARGE_FILE;
    }

    /**
     * Compact human label, e.g. {@code "QUIC (TLS 1.3) · 8s/1p · 100 MB"}, or for
     * a frame run {@code "TCP · 4s/1p · 4k×3000 @24fps"}.
     *
     * <p>Multi-file runs describe themselves by their frame budget rather than a
     * byte target or duration, because that is what actually bounds them.
     */
    public String shortLabel() {
        Protocol p = fromWire(protocol);
        StringBuilder sb = new StringBuilder(p == null ? protocol : p.label);
        if (p != null && p.supportsTlsToggle() && tls) sb.append("+TLS");
        // Multi-file always runs on OS threads (per-frame file I/O is blocking),
        // so the Threaded/Selector distinction is not meaningful there.
        if ((p == null || !p.isQuic()) && !isMultiFile()) {
            sb.append(" · ").append("Selector".equals(architecture) ? "Sel" : "Thr");
        }
        sb.append(" · ").append(threads).append("s/").append(processes).append("p");
        if (isMultiFile()) {
            sb.append(" · ").append(frame.shortLabel());
        } else if (continuous) {
            sb.append(" · cont");
        } else if (bytesTarget > 0) {
            sb.append(" · ").append(humanBytes(bytesTarget));
        }
        return sb.toString();
    }

    public static String humanBytes(long b) {
        if (b >= 1024L * 1024 * 1024) return (b / (1024L * 1024 * 1024)) + " GB";
        if (b >= 1024L * 1024) return (b / (1024L * 1024)) + " MB";
        if (b >= 1024) return (b / 1024) + " KB";
        return b + " B";
    }

    public static Protocol fromWire(String wire) {
        for (Protocol p : Protocol.values()) {
            if (p.wire.equals(wire)) return p;
        }
        return null;
    }
}

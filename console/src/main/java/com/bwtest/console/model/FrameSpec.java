package com.bwtest.console.model;

/**
 * The frametest-compatible half of a scenario — mirrors the Rust
 * {@code FrameSpec} field for field, camelCase so Jackson serialises straight
 * onto the wire. Only meaningful when the scenario's transfer mode is
 * multi-file.
 *
 * <p>Two flag meanings are easy to misremember and worth stating here, because
 * getting them wrong is worse than not having them: {@code -o} is out-of-order
 * I/O completion (not an output file — CSV export is {@code -x}), and {@code -v}
 * is reverse access order (not verbose).
 */
public class FrameSpec {

    /**
     * Named frame-size presets, matching frametest's {@code -w sd|hd|2k|4k}.
     *
     * <p>The 2K and 4K byte counts are <em>verified</em> byte-exact against both
     * the DVS reference output (which echoes {@code -w12512} for {@code -w 2k})
     * and the tframetest clone. SD and HD are <em>inferred</em> from the clone's
     * profiles — no source documents the original's values, and real SD DPX is
     * 720x486 rather than 720x480, so treat those two as approximate.
     *
     * <p>Geometry is full-aperture DPX at 4 bytes/pixel, which is also how
     * 10-bit DPX packs (3x10 bits into one 32-bit word). Payloads are rounded up
     * to a 4096-byte boundary for direct I/O; only SD is actually affected.
     */
    public enum Preset {
        SD("sd", "SD  720x480", 720L * 480 * 4, false),
        HD("hd", "HD  1920x1080", 1920L * 1080 * 4, false),
        TWO_K("2k", "2K  2048x1556", 2048L * 1556 * 4, true),
        FOUR_K("4k", "4K  4096x3112", 4096L * 3112 * 4, true),
        CUSTOM("custom", "Custom", 0, true);

        public final String key;
        public final String label;
        private final long rawPayload;
        /** Whether this preset's byte count is verified against a real source. */
        public final boolean verified;

        Preset(String key, String label, long rawPayload, boolean verified) {
            this.key = key;
            this.label = label;
            this.rawPayload = rawPayload;
            this.verified = verified;
        }

        /** Payload bytes, aligned up for direct I/O. */
        public long payloadBytes() {
            return align(rawPayload, 4096);
        }

        /** Whole-file bytes including the default 64 KB header. */
        public long frameBytes() {
            return payloadBytes() + 64L * 1024;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public static long align(long n, long to) {
        if (to <= 0 || n <= 0) return n;
        return ((n + to - 1) / to) * to;
    }

    // --- Fields mirroring the Rust FrameSpec (camelCase on the wire) ---

    public String mode = FrameMode.WRITE.wire;
    /** Total bytes per frame including the header. */
    public long frameBytes = Preset.FOUR_K.frameBytes();
    /** {@code -n}. */
    public long frameCount = 1800;
    /** {@code -f} target frames per second; 0 = unpaced. */
    public double fpsLimit = 0;
    /** {@code -q} frames queued before one is counted dropped; 0 = unbounded. */
    public int queueDepth = 0;
    /** {@code -b} frames staged before the clock starts. */
    public int prebuffer = 5;
    public String order = FrameOrder.SEQUENTIAL.wire;
    public String storage = FrameStorage.DISK.wire;
    /** Directory frames are read from / written to on the sender. */
    public String path = "";
    /** Where the receiver writes received frames; empty = same as {@code path}. */
    public String destPath = "";
    /** {@code --header} per-file header size in KB. */
    public int headerKb = 64;
    /** {@code -d} files per subdirectory; 0 = one flat directory. */
    public int filesPerDir = 0;
    /** {@code --name} filename pattern of the first file; empty = frame%06u.tst. */
    public String namePattern = "";
    /** {@code -a} overlapped I/O depth; 0 = synchronous. */
    public int asyncDepth = 0;
    /** {@code -o} allow I/Os to complete out of order. */
    public boolean outOfOrder = false;
    /** {@code -l} extra loops over the frame set. */
    public long loopFrames = 0;
    /** {@code -i} I/O calls per frame. */
    public int iosPerFrame = 1;
    /** {@code -c} read only the middle N% of each frame; 0/100 = whole frame. */
    public int cropPercent = 0;
    /** {@code -p} sleep before starting, letting caches settle. */
    public int pauseSecs = 0;
    /** {@code --prealloc} frames of space to preallocate in streaming mode. */
    public long prealloc = 0;
    /** Bypass the page cache (O_DIRECT / F_NOCACHE / FILE_FLAG_NO_BUFFERING). */
    public boolean directIo = true;

    public FrameSpec() {}

    /** Payload bytes per frame, i.e. the file minus its header. */
    public long payloadBytes() {
        return Math.max(0, frameBytes - headerKb * 1024L);
    }

    /** Total frames including {@code -l} loops. */
    public long totalFrames() {
        return frameCount * (loopFrames + 1);
    }

    /** Total bytes the run will move, ignoring drops. */
    public long totalBytes() {
        return totalFrames() * payloadBytes();
    }

    public FrameMode modeEnum() {
        for (FrameMode m : FrameMode.values()) if (m.wire.equals(mode)) return m;
        return FrameMode.WRITE;
    }

    public FrameStorage storageEnum() {
        for (FrameStorage s : FrameStorage.values()) if (s.wire.equals(storage)) return s;
        return FrameStorage.DISK;
    }

    public FrameOrder orderEnum() {
        for (FrameOrder o : FrameOrder.values()) if (o.wire.equals(order)) return o;
        return FrameOrder.SEQUENTIAL;
    }

    /** The preset this frame size corresponds to, or CUSTOM if none matches. */
    public Preset presetOf() {
        for (Preset p : Preset.values()) {
            if (p != Preset.CUSTOM && p.frameBytes() == frameBytes) return p;
        }
        return Preset.CUSTOM;
    }

    /** Compact label for the run list, e.g. {@code 4k x3000 @24fps}. */
    public String shortLabel() {
        Preset p = presetOf();
        StringBuilder sb = new StringBuilder(p == Preset.CUSTOM
                ? Scenario.humanBytes(frameBytes) : p.key);
        sb.append("×").append(totalFrames());
        if (fpsLimit > 0) sb.append(" @").append((long) fpsLimit).append("fps");
        if (storageEnum() == FrameStorage.MEMORY) sb.append(" mem");
        if (orderEnum() != FrameOrder.SEQUENTIAL) {
            sb.append(' ').append(orderEnum().label.toLowerCase());
        }
        return sb.toString();
    }
}

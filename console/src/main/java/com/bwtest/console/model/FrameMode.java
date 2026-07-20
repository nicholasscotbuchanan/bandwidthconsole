package com.bwtest.console.model;

/** frametest's test mode: {@code -w} write, {@code -r} read, {@code -e} empty. */
public enum FrameMode {
    WRITE("Write", "Write frames"),
    /** frametest's default: read pre-existing frames from the source path. */
    READ("Read", "Read frames"),
    /** Zero-length frames — isolates open/close cost, i.e. a metadata/IOPS test. */
    EMPTY("Empty", "Empty (open/close only)");

    public final String wire;
    public final String label;

    FrameMode(String wire, String label) {
        this.wire = wire;
        this.label = label;
    }

    /** Read mode needs frames to already exist at the path. */
    public boolean needsExistingFrames() {
        return this == READ;
    }

    @Override
    public String toString() {
        return label;
    }
}

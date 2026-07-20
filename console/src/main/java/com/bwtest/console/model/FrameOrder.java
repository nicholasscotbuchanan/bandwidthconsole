package com.bwtest.console.model;

/**
 * The order frames are created or accessed in. Sequential is the default;
 * {@code -v} reverses and {@code -m} randomises.
 *
 * <p>Random order is the interesting one on shared storage: it defeats
 * read-ahead, so the gap between it and sequential is a direct measure of how
 * much your throughput depends on prefetching.
 */
public enum FrameOrder {
    SEQUENTIAL("Sequential", "Sequential"),
    REVERSE("Reverse", "Reverse"),
    RANDOM("Random", "Random");

    public final String wire;
    public final String label;

    FrameOrder(String wire, String label) {
        this.wire = wire;
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}

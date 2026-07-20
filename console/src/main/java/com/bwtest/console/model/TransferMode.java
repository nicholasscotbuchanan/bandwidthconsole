package com.bwtest.console.model;

/**
 * What <em>shape</em> of data crosses the wire.
 *
 * <p>This is the control that answers the question the tool was extended for:
 * how much bandwidth do you lose moving thousands of discrete frame files
 * instead of one continuous stream? Run the same scenario twice — once each way
 * — and the comparison table shows you the cost directly.
 *
 * <p>{@code wire} is the exact string the Rust agent's serde enum expects.
 */
public enum TransferMode {
    /** One continuous byte stream. The original behaviour, and what DVS
     *  frametest calls streaming mode ({@code -s}). */
    LARGE_FILE("LargeFile", "Large file — one stream"),
    /** Discrete frames, each paying open/IO/close on both ends, paced against an
     *  fps deadline with frametest's queue-depth drop accounting. */
    MULTI_FILE("MultiFile", "Multi-file — frametest");

    public final String wire;
    public final String label;

    TransferMode(String wire, String label) {
        this.wire = wire;
        this.label = label;
    }

    public boolean isMultiFile() {
        return this == MULTI_FILE;
    }

    @Override
    public String toString() {
        return label;
    }
}

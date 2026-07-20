package com.bwtest.console.model;

/**
 * Whether frames actually touch a filesystem.
 *
 * <p>This is the A/B that separates two costs the aggregate number hides. In
 * {@link #DISK} the source reads or creates real frame files and the sink writes
 * them, so the run measures storage <em>and</em> network together — faithful to
 * frametest. In {@link #MEMORY} frames are synthesised in RAM and discarded at
 * the sink, leaving only the network cost of many small transfers.
 *
 * <p>Run both and compare the Gantt's I/O band: if it collapses in Memory mode
 * but throughput barely moves, your disks were never the problem.
 */
public enum FrameStorage {
    DISK("Disk", "Disk (real files)"),
    MEMORY("Memory", "Memory (no disk)");

    public final String wire;
    public final String label;

    FrameStorage(String wire, String label) {
        this.wire = wire;
        this.label = label;
    }

    public boolean isDisk() {
        return this == DISK;
    }

    @Override
    public String toString() {
        return label;
    }
}

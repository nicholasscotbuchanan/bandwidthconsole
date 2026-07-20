package com.bwtest.console.model;

/** Sender generation model — the core thing the tool compares. */
public enum Architecture {
    THREADED("Threaded", "Threaded (blocking, 1 thread/conn)"),
    SELECTOR("Selector", "Selector (async reactor)");

    public final String wire;
    public final String label;

    Architecture(String wire, String label) {
        this.wire = wire;
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}

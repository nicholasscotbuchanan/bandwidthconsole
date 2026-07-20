package com.bwtest.console.model;

import java.util.List;

/** One streamed sample and the final aggregates, as parsed from the agent. */
public final class Telemetry {

    /** One streamed sample. {@code perStream} carries each stream's goodput for
     *  the interval, so the per-stream chart can plot them individually.
     *  {@code role} is which end measured it: "source" (offered throughput) or
     *  "sink" (delivered goodput) — the two halves of the balance view.
     *  {@code frame} is null on large-file runs, which lets a view tell
     *  "not a frame run" apart from "a frame run with zero frames so far". */
    public record Sample(String runId, String role, double tSecs, double mbps, double pps,
                         double rttMs, long retransmits, double cpuPercent,
                         List<Double> perStream, FrameProgress frame) {
        public boolean fromSink() { return "sink".equals(role); }
        public boolean hasFrames() { return frame != null; }
    }

    /** Where a multi-file run is against its frame budget, and where the
     *  per-frame time is going, for one sampling interval. */
    public record FrameProgress(double fps, long framesDone, long framesDropped,
                                double frameMsAvg, double openMsAvg, double ioMsAvg,
                                double closeMsAvg) {
        /** Filesystem time per frame — open + transfer + close. */
        public double fsMs() { return openMsAvg + ioMsAvg + closeMsAvg; }
    }

    /**
     * The latency waterfall. The last three fields split per-frame time into
     * filesystem work at each end and time on the wire, which is what makes it
     * possible to see at a glance whether a slow multi-file run is losing time
     * to the disk or to the network.
     */
    public record Phases(double connectMs, double handshakeMs, double firstByteMs,
                         double rampMs, double steadyMs, double teardownMs,
                         double srcIoMs, double sinkIoMs, double netMs) {
        public double total() {
            return connectMs + handshakeMs + firstByteMs + rampMs + steadyMs + teardownMs;
        }
        /** Mean per-frame time accounted for by the three-way I/O split. */
        public double perFrameTotal() {
            return srcIoMs + sinkIoMs + netMs;
        }
        public boolean hasIoBreakdown() {
            return perFrameTotal() > 0;
        }
    }

    /** min/avg/max for one timed stage, milliseconds. */
    public record Stage(double minMs, double avgMs, double maxMs) {
        public static final Stage ZERO = new Stage(0, 0, 0);
    }

    /** One row of frametest's "Averaged details" table. */
    public record Window(String label, double openMs, double ioMs, double frameMs,
                         double mbPerSec, double fps) {}

    /**
     * Everything frametest reports at the end of a run; null on large-file runs.
     *
     * <p>{@code histogram} holds 13 counts against frametest's own fixed bucket
     * edges, so the distribution is directly comparable to output from a real
     * frametest run.
     */
    public record FrameStats(long framesTransferred, long framesDropped, long bytesTotal,
                             double fastestMs, double slowestMs,
                             Stage file, Stage create, Stage io, Stage close,
                             List<Long> histogram, List<Window> windows) {

        /** frametest's histogram bucket labels, in milliseconds. */
        public static final String[] BUCKETS = {
                "<0.1", ".2", ".5", "1", "2", "5", "10", "20", "50", "100", "200", "500", ">1s"
        };

        public long framesOffered() { return framesTransferred + framesDropped; }

        /** Fraction of frames that missed their deadline, 0..1. */
        public double dropRate() {
            long offered = framesOffered();
            return offered == 0 ? 0 : (double) framesDropped / offered;
        }

        public double mbPerSec(double elapsedSecs) {
            return elapsedSecs <= 0 ? 0 : bytesTotal / (1024.0 * 1024.0) / elapsedSecs;
        }

        /** MB/s implied by a single frame of {@code frameBytes} taking {@code ms}. */
        public static double rateFor(long frameBytes, double ms) {
            return ms <= 0 ? 0 : (frameBytes / (1024.0 * 1024.0)) / (ms / 1000.0);
        }
    }

    public record Summary(String runId, double avgMbps, double peakMbps, long bytesTotal,
                          double p50RttMs, double p95RttMs, double p99RttMs,
                          long retransmits, boolean sackActive, Phases phases,
                          FrameStats frame) {
        public boolean hasFrames() { return frame != null; }
    }

    private Telemetry() {}
}

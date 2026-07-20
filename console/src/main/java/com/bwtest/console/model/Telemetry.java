package com.bwtest.console.model;

import java.util.List;

/** One streamed sample and the final aggregates, as parsed from the agent. */
public final class Telemetry {

    /** One streamed sample. {@code perStream} carries each stream's goodput for
     *  the interval, so the per-stream chart can plot them individually.
     *  {@code end} is which end measured it: "send" (offered throughput) or
     *  "recv" (delivered goodput) — the two halves of the balance view.
     *  {@code frame} is null on large-file runs, which lets a view tell
     *  "not a frame run" apart from "a frame run with zero frames so far". */
    public record Sample(String runId, String end, double tSecs, double mbps, double pps,
                         double rttMs, long retransmits, double cpuPercent,
                         List<Double> perStream, FrameProgress frame) {
        public boolean fromReceiver() { return "recv".equals(end); }
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
                         double sendIoMs, double recvIoMs, double netMs) {
        public double total() {
            return connectMs + handshakeMs + firstByteMs + rampMs + steadyMs + teardownMs;
        }
        /** Mean per-frame time accounted for by the three-way I/O split. */
        public double perFrameTotal() {
            return sendIoMs + recvIoMs + netMs;
        }
        public boolean hasIoBreakdown() {
            return perFrameTotal() > 0;
        }
    }

    /**
     * One stage of a frame's lifecycle, as a Gantt lane.
     *
     * <p>A frame is generated on the sender's disk, read back, put on the wire,
     * received at the receiver, and written out there. These overlap heavily — while
     * one frame is in flight the next is being read and the previous is being
     * written — so each is its own lane with a real wall-clock extent rather
     * than a slice of a serial pipeline.
     */
    public enum Lane {
        GENERATE("generate", "Generate", "staging frames on sender disk"),
        READ("read", "Read", "reading staged frames back"),
        TRANSMIT("transmit", "Transmit", "handing frames to the transport"),
        RECEIVE("receive", "Receive", "pulling frames off the transport"),
        WRITE("write", "Write-out", "writing frames to receiver disk");

        /** Wire name, as serialised by the agent. */
        public final String wire;
        public final String label;
        /** What this lane is actually timing, for the legend. */
        public final String detail;

        Lane(String wire, String label, String detail) {
            this.wire = wire;
            this.label = label;
            this.detail = detail;
        }

        /** Lifecycle order, which is also the order lanes are drawn. */
        public static final Lane[] LIFECYCLE =
                {GENERATE, READ, TRANSMIT, RECEIVE, WRITE};

        /** Parse a wire name, or null if the agent sent one we don't know. */
        public static Lane fromWire(String s) {
            for (Lane l : values()) {
                if (l.wire.equals(s)) return l;
            }
            return null;
        }

        /** Lanes the receiver measures; the rest are the sender's. */
        public boolean isReceiveSide() {
            return this == RECEIVE || this == WRITE;
        }
    }

    /**
     * Live progress of one lifecycle lane.
     *
     * <p>{@code startMs}/{@code endMs} are offsets from the reporting agent's
     * run epoch. Sender and receiver clocks are not synchronised, so the console
     * anchors each end's timeline on its own arrival rather than trusting the
     * two to share an origin: spacing within a end is exact, alignment across
     * roles is approximate. That is honest for a view whose purpose is showing
     * which stage dominates, not measuring one-way delay.
     */
    public record LaneUpdate(String runId, String end, Lane lane, double startMs,
                             double endMs, double busyMs, long done, long total,
                             boolean complete) {

        /** Wall-clock extent of the lane. */
        public double spanMs() {
            return Math.max(0, endMs - startMs);
        }

        /**
         * Mean in-lane time per frame — this stage's per-frame cost.
         * Less than {@link #spanMs()} divided by frames whenever the lane waits
         * on another stage, which is how "slow" is told apart from "idle".
         */
        public double perFrameMs() {
            return done == 0 ? 0 : busyMs / done;
        }

        /** Share of the lane's wall-clock extent actually spent working, 0..1. */
        public double duty() {
            double span = spanMs();
            return span <= 0 ? 0 : Math.min(1.0, busyMs / span);
        }

        public double fraction() {
            return total == 0 ? 0 : Math.min(1.0, (double) done / total);
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
                          FrameStats frame, List<LaneUpdate> lanes) {
        public boolean hasFrames() { return frame != null; }
    }

    private Telemetry() {}
}

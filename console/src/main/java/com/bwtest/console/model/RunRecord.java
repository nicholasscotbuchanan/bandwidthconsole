package com.bwtest.console.model;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.ObservableMap;
import javafx.scene.paint.Color;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** A single test run: its config, the live sample stream, and the final result.
 *  This is the unit compared in the 3D field and the comparison table. */
public class RunRecord {
    public enum State { PREPARING, RUNNING, DONE, ERROR, ABORTED }

    public final String id;
    public final Scenario scenario;
    public final String fromName;
    public final String toName;
    public final Color color;
    public final int index; // stable ordinal for labelling
    /** Non-null when this run is one leg of a fan-out; all legs of the same
     *  fan-out share the id, so views can group and aggregate them. */
    public String groupId;
    /** This leg's position within its fan-out, 1-based. 0 for a solo run. */
    public int groupLeg;
    /** How many legs the fan-out has in total. 0 for a solo run. */
    public int groupSize;

    /** Sender-side samples: offered throughput, per-stream goodput, RTT, CPU. */
    public final ObservableList<Telemetry.Sample> samples = FXCollections.observableArrayList();
    /** Receiver-side samples: what actually arrived (delivered goodput). */
    public final ObservableList<Telemetry.Sample> recvSamples = FXCollections.observableArrayList();
    /**
     * Latest state of each lifecycle lane, keyed by lane. Replaced wholesale as
     * updates stream in, so the Gantt can build progressively instead of waiting
     * for the run to finish. Sender and receiver both write here — they own disjoint
     * lanes, so there is no contention over a key.
     */
    public final ObservableMap<Telemetry.Lane, Telemetry.LaneUpdate> lanes =
            FXCollections.observableHashMap();

    /**
     * Console-side wall clock, milliseconds, at which each end's lane timeline
     * begins. The agents' epochs are unsynchronised, so a lane's reported
     * offsets are rebased onto the moment the console first heard from that
     * end. Within a end this changes nothing; across roles it is what lets
     * sender and receiver lanes share one axis at all.
     */
    private final Map<String, Long> roleEpoch = new ConcurrentHashMap<>();

    private final ObjectProperty<Telemetry.Summary> summary = new SimpleObjectProperty<>();
    private final ObjectProperty<State> state = new SimpleObjectProperty<>(State.PREPARING);
    private final StringProperty message = new SimpleStringProperty("");

    public RunRecord(String id, int index, Scenario scenario, String fromName,
                     String toName, Color color) {
        this.id = id;
        this.index = index;
        this.scenario = scenario;
        this.fromName = fromName;
        this.toName = toName;
        this.color = color;
    }

    public ObjectProperty<Telemetry.Summary> summaryProperty() { return summary; }
    public Telemetry.Summary getSummary() { return summary.get(); }
    public void setSummary(Telemetry.Summary s) { summary.set(s); }

    public ObjectProperty<State> stateProperty() { return state; }
    public State getState() { return state.get(); }
    public void setState(State s) { state.set(s); }

    public StringProperty messageProperty() { return message; }
    public void setMessage(String m) { message.set(m); }

    /**
     * Record a lane update, pinning that end's origin on first sight.
     *
     * <p>The first update from a end establishes when its clock started: the
     * reported {@code startMs} is taken to mean "this long before now". Offsets
     * are stored exactly as the agent reported them and only rebased when read,
     * because a end that appears later can move the shared origin — rebasing
     * on write would leave everything stored before it misaligned.
     *
     * <p>Control-channel latency therefore lands in the cross-end alignment,
     * where it is milliseconds against stages measured in seconds, and it does
     * not accumulate.
     */
    public void applyLane(Telemetry.LaneUpdate u) {
        applyLane(u, System.currentTimeMillis());
    }

    /**
     * {@link #applyLane(Telemetry.LaneUpdate)} with the arrival instant supplied
     * rather than read from the clock.
     *
     * <p>Epoch inference is a function of when an update arrives, so anything
     * replaying a run — fixtures, tests, a future history reload — has to be
     * able to say when each update would have landed instead of having "now"
     * stamped on all of them at once.
     */
    public void applyLane(Telemetry.LaneUpdate u, long arrivedAtMillis) {
        roleEpoch.computeIfAbsent(u.end(), r -> arrivedAtMillis - (long) u.startMs());
        lanes.put(u.lane(), u);
    }

    /**
     * Lifecycle lanes in draw order, rebased onto one timeline shared by both
     * roles, skipping any stage the run never touched.
     */
    public java.util.List<Telemetry.LaneUpdate> lifecycleLanes() {
        long base = Long.MAX_VALUE;
        for (long v : roleEpoch.values()) base = Math.min(base, v);

        java.util.List<Telemetry.LaneUpdate> out = new java.util.ArrayList<>();
        for (Telemetry.Lane l : Telemetry.Lane.LIFECYCLE) {
            Telemetry.LaneUpdate u = lanes.get(l);
            if (u == null) continue;
            Long epoch = roleEpoch.get(u.end());
            double shift = (epoch == null || base == Long.MAX_VALUE) ? 0 : epoch - base;
            out.add(shift == 0 ? u : new Telemetry.LaneUpdate(u.runId(), u.end(), u.lane(),
                    u.startMs() + shift, u.endMs() + shift, u.busyMs(),
                    u.done(), u.total(), u.complete()));
        }
        return out;
    }

    public boolean isFanoutLeg() {
        return groupId != null;
    }

    public String label() {
        String base = "#" + index + "  " + scenario.shortLabel();
        // A fan-out leg is only meaningful alongside its siblings, so say which
        // one it is rather than leaving several identical-looking rows.
        return isFanoutLeg() ? base + "  [" + groupLeg + "/" + groupSize + " " + fromName + "]"
                             : base;
    }

    /** Frames transferred, or 0 for a large-file run or one still in flight. */
    public long framesTransferred() {
        Telemetry.Summary s = getSummary();
        if (s != null && s.hasFrames()) return s.frame().framesTransferred();
        for (int i = samples.size() - 1; i >= 0; i--) {
            Telemetry.FrameProgress f = samples.get(i).frame();
            if (f != null) return f.framesDone();
        }
        return 0;
    }

    /** Frames dropped for missing their deadline. */
    public long framesDropped() {
        Telemetry.Summary s = getSummary();
        if (s != null && s.hasFrames()) return s.frame().framesDropped();
        for (int i = samples.size() - 1; i >= 0; i--) {
            Telemetry.FrameProgress f = samples.get(i).frame();
            if (f != null) return f.framesDropped();
        }
        return 0;
    }

    public double peakMbps() {
        Telemetry.Summary s = getSummary();
        if (s != null) return s.peakMbps();
        return samples.stream().mapToDouble(Telemetry.Sample::mbps).max().orElse(0);
    }
}

package com.bwtest.console.model;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.paint.Color;

/** A single test run: its config, the live sample stream, and the final result.
 *  This is the unit compared in the 3D field and the comparison table. */
public class RunRecord {
    public enum State { PREPARING, RUNNING, DONE, ERROR, ABORTED }

    public final String id;
    public final Scenario scenario;
    public final String sourceName;
    public final String sinkName;
    public final Color color;
    public final int index; // stable ordinal for labelling
    /** Non-null when this run is one leg of a fan-out; all legs of the same
     *  fan-out share the id, so views can group and aggregate them. */
    public String groupId;
    /** This leg's position within its fan-out, 1-based. 0 for a solo run. */
    public int groupLeg;
    /** How many legs the fan-out has in total. 0 for a solo run. */
    public int groupSize;

    /** Source-side samples: offered throughput, per-stream goodput, RTT, CPU. */
    public final ObservableList<Telemetry.Sample> samples = FXCollections.observableArrayList();
    /** Sink-side samples: what actually arrived (delivered goodput). */
    public final ObservableList<Telemetry.Sample> sinkSamples = FXCollections.observableArrayList();
    private final ObjectProperty<Telemetry.Summary> summary = new SimpleObjectProperty<>();
    private final ObjectProperty<State> state = new SimpleObjectProperty<>(State.PREPARING);
    private final StringProperty message = new SimpleStringProperty("");

    public RunRecord(String id, int index, Scenario scenario, String sourceName,
                     String sinkName, Color color) {
        this.id = id;
        this.index = index;
        this.scenario = scenario;
        this.sourceName = sourceName;
        this.sinkName = sinkName;
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

    public boolean isFanoutLeg() {
        return groupId != null;
    }

    public String label() {
        String base = "#" + index + "  " + scenario.shortLabel();
        // A fan-out leg is only meaningful alongside its siblings, so say which
        // one it is rather than leaving several identical-looking rows.
        return isFanoutLeg() ? base + "  [" + groupLeg + "/" + groupSize + " " + sourceName + "]"
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

package com.bwtest.console.ui;

import com.bwtest.console.model.RunRecord;
import com.bwtest.console.model.Telemetry;
import javafx.beans.property.ObjectProperty;
import javafx.collections.ObservableList;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;

import java.util.List;

/**
 * Left live view: how much bandwidth each thread (stream) is contributing,
 * as it happens.
 *
 *   X — thread / stream index
 *   Z — time, newest row at the front
 *   Y — that stream's goodput for the interval, Mbit/s
 *
 * A flat, even skyline means the load generator is spreading work fairly;
 * ragged rows point at scheduler or reactor imbalance — exactly the
 * Threaded-vs-Selector difference the tool exists to show.
 */
public class ThreadContribution3D extends Live3DPane {

    /** Rows of history kept on the floor (newest at the front edge). */
    private static final int WINDOW = 20;

    public ThreadContribution3D(ObservableList<RunRecord> runs,
                                ObjectProperty<RunRecord> selected) {
        super("PER-THREAD CONTRIBUTION",
              "X → thread      Z → time (new at front)      Y → Mbit/s", runs, selected);
    }

    @Override
    protected void rebuild(RunRecord run) {
        content.getChildren().add(buildFloor());
        List<Telemetry.Sample> samples = run == null ? List.of() : run.samples;
        if (samples.isEmpty()) {
            caption.setText("waiting for a running test…");
            return;
        }

        int from = Math.max(0, samples.size() - WINDOW);
        List<Telemetry.Sample> window = samples.subList(from, samples.size());
        int streams = 0;
        List<Double> all = new java.util.ArrayList<>();
        for (Telemetry.Sample s : window) {
            streams = Math.max(streams, s.perStream().size());
            all.addAll(s.perStream());
        }
        double maxV = robustMax(all);
        if (streams == 0) {
            caption.setText("no per-stream telemetry in these samples");
            return;
        }

        Telemetry.Sample latest = window.get(window.size() - 1);
        caption.setText(streams + " threads · " + fmt(latest.mbps()) + " total · "
                + "top stream " + fmt(latest.perStream().stream()
                        .mapToDouble(Double::doubleValue).max().orElse(0)));

        // Height grid: rows are time (newest at the front, +Z), columns are
        // threads. A single stream still gets a 2-wide grid so it reads as a
        // ribbon rather than a line.
        int rows = window.size();
        int cols = Math.max(2, streams);
        double[][] h = new double[rows][cols];
        for (int r = 0; r < rows; r++) {          // r: oldest (back) → newest (front)
            Telemetry.Sample s = window.get(r);
            for (int k = 0; k < cols; k++) {
                double v = k < s.perStream().size() ? s.perStream().get(k)
                        : (streams == 1 ? s.perStream().get(0) : 0);
                h[r][k] = Math.min(v / maxV, 1.0) * MAX_BAR;
            }
        }
        double zFront = SPAN * 0.82, zBack = axisPos(WINDOW - rows, WINDOW);
        content.getChildren().add(surface(h, -SPAN * 0.82, SPAN * 0.82,
                zBack, zFront, run.color));
        content.getChildren().add(wire(h, -SPAN * 0.82, SPAN * 0.82,
                zBack, zFront, run.color.brighter()));

        // Thread indices along the front edge (thin out when crowded).
        int step = Math.max(1, streams / 8);
        for (int k = 0; k < streams; k += step) {
            Text t = flatText(String.valueOf(k), "#9fb3d1", 18);
            t.setTranslateX(t.getTranslateX() + axisPos(k, streams));
            t.setTranslateZ(SPAN + 36);
            content.getChildren().add(t);
        }
        Text lbl = flatText("thread", "#5b7392", 18);
        lbl.setTranslateZ(SPAN + 74);
        content.getChildren().add(lbl);
        Text now = flatText("now →", "#5b7392", 18);
        now.setTranslateX(now.getTranslateX() - SPAN - 70);
        now.setTranslateZ(SPAN * 0.82);
        content.getChildren().add(now);
    }
}

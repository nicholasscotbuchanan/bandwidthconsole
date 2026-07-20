package com.bwtest.console.ui;

import com.bwtest.console.model.RunRecord;
import com.bwtest.console.model.Telemetry;
import javafx.beans.property.ObjectProperty;
import javafx.collections.ObservableList;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;

import java.util.List;

/**
 * Right live view: goodput vs throughput — is the sink keeping up with the
 * source?
 *
 *   X — time, newest column on the right
 *   Z — two rows: SENT (source, offered throughput) behind,
 *       RECEIVED (sink, delivered goodput) in front
 *   Y — Mbit/s
 *
 * When the pairs match height the path is clean; a front row persistently
 * shorter than the back row is bandwidth lost between the ends (loss, queue
 * collapse, a receiver that can't drain). The received row is tinted by that
 * ratio — teal when balanced, amber when slipping, red when the sink is
 * drowning.
 */
public class Balance3D extends Live3DPane {

    private static final int WINDOW = 20;
    private static final Color SENT = Color.web("#60a5fa");

    public Balance3D(ObservableList<RunRecord> runs, ObjectProperty<RunRecord> selected) {
        super("GOODPUT VS THROUGHPUT",
              "X → time      Z → sent | received      Y → Mbit/s", runs, selected);
    }

    @Override
    protected void rebuild(RunRecord run) {
        content.getChildren().add(buildFloor());
        List<Telemetry.Sample> src = run == null ? List.of() : run.samples;
        if (src.isEmpty()) {
            caption.setText("waiting for a running test…");
            return;
        }
        List<Telemetry.Sample> snk = run.sinkSamples;

        int from = Math.max(0, src.size() - WINDOW);
        List<Telemetry.Sample> window = src.subList(from, src.size());
        List<Double> all = new java.util.ArrayList<>();
        for (Telemetry.Sample s : window) all.add(s.mbps());
        for (Telemetry.Sample s : snk) all.add(s.mbps());
        double maxV = robustMax(all);

        // Two surface ribbons along time: SENT behind, RECEIVED in front.
        int cols = window.size();
        double sentSum = 0, recvSum = 0;
        double[][] hSent = new double[2][cols];
        double[][] hRecv = new double[2][cols];
        for (int i = 0; i < cols; i++) {
            Telemetry.Sample s = window.get(i);
            double sent = s.mbps();
            double recv = Math.max(0, nearestMbps(snk, s.tSecs()));
            sentSum += sent;
            recvSum += recv;
            hSent[0][i] = hSent[1][i] = Math.min(sent / maxV, 1.0) * MAX_BAR;
            hRecv[0][i] = hRecv[1][i] = Math.min(recv / maxV, 1.0) * MAX_BAR;
        }
        double xL = -SPAN * 0.82, xR = axisPos(cols - 1, WINDOW);
        double half = SPAN * 0.28;
        double zSent = -SPAN * 0.38, zRecv = SPAN * 0.38;
        double ratio = sentSum > 1e-9 ? recvSum / sentSum : 1.0;
        addRibbon(hSent, xL, xR, zSent, half, SENT);
        if (!snk.isEmpty()) {
            addRibbon(hRecv, xL, xR, zRecv, half, ratioColor(ratio));
        }

        if (snk.isEmpty()) {
            caption.setText("no sink telemetry — agents predate the balance feed?");
        } else {
            double balance = sentSum > 1e-9 ? clamp(recvSum / sentSum, 0, 1.5) * 100 : 100;
            caption.setText(String.format("sent %s · received %s · balance %.1f%%  (%s)",
                    fmt(sentSum / Math.max(1, cols)), fmt(recvSum / Math.max(1, cols)),
                    balance, run.sourceName + " → " + run.sinkName));
        }

        Text sentLbl = flatText("sent", "#7ea6d8", 20);
        sentLbl.setTranslateX(sentLbl.getTranslateX() - SPAN - 70);
        sentLbl.setTranslateZ(zSent);
        Text recvLbl = flatText("received", "#6fd8be", 20);
        recvLbl.setTranslateX(recvLbl.getTranslateX() - SPAN - 82);
        recvLbl.setTranslateZ(zRecv);
        Text now = flatText("now →", "#5b7392", 18);
        now.setTranslateX(now.getTranslateX() + SPAN * 0.82);
        now.setTranslateZ(SPAN + 40);
        content.getChildren().addAll(sentLbl, recvLbl, now);
    }

    /** An extruded area ribbon: flat roof at the values plus front/back curtains
     *  down to the floor, so it reads as a solid surface, not a floating sheet. */
    private void addRibbon(double[][] h, double xL, double xR, double z, double half, Color c) {
        content.getChildren().add(surface(h, xL, xR, z - half, z + half, c));
        content.getChildren().add(wire(h, xL, xR, z - half, z + half, c.brighter()));
        double[][] curtain = {h[0], new double[h[0].length]};
        Color side = c.interpolate(Color.web("#0b1220"), 0.35);
        content.getChildren().add(surface(curtain, xL, xR, z + half, z + half, side));
        content.getChildren().add(surface(curtain, xL, xR, z - half, z - half, side));
    }

    /** Sink sample nearest in run-time to `t`, or -1 when none is close enough. */
    private static double nearestMbps(List<Telemetry.Sample> sink, double t) {
        double best = -1, bestDt = 0.6; // pair only within ~3 sample periods
        for (int i = sink.size() - 1; i >= 0; i--) {
            double dt = Math.abs(sink.get(i).tSecs() - t);
            if (dt < bestDt) {
                bestDt = dt;
                best = sink.get(i).mbps();
            } else if (sink.get(i).tSecs() < t - 1.0) {
                break; // sorted by time; nothing older will be closer
            }
        }
        return best;
    }

    /** Delivered/offered ratio → health colour. */
    private static Color ratioColor(double r) {
        if (r >= 0.97) return Color.web("#5eead4");
        if (r >= 0.85) return Color.web("#facc15");
        return Color.web("#f87171");
    }
}

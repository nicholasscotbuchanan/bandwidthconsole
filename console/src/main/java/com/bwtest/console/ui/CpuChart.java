package com.bwtest.console.ui;

import com.bwtest.console.model.RunRecord;
import com.bwtest.console.model.Telemetry;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;

/**
 * Agent-process CPU% per edge node, overlaid per run. Both ends of a run
 * report their own CPU: the source (solid line) pays for generating and
 * encrypting load, the sink (dashed line) for absorbing it. This is where a
 * protocol choice shows its cost — TLS and userspace QUIC burn measurably
 * more CPU per bit than plain kernel TCP, on both nodes — and a node pinned
 * at its core count while the other idles is the balance tell.
 */
public class CpuChart extends StackPane {

    private final LineChart<Number, Number> chart;
    private final NumberAxis x;
    private final TimelineSlider timeline = new TimelineSlider();
    /** Colour per series, in insertion order — for legend symbol recolouring. */
    private final java.util.List<Color> seriesColors = new java.util.ArrayList<>();

    public CpuChart(ObservableList<RunRecord> runs) {
        x = new NumberAxis();
        x.setLabel("time (s)");
        NumberAxis y = new NumberAxis();
        y.setLabel("agent process CPU (%)");
        y.setForceZeroInRange(true);

        chart = new LineChart<>(x, y);
        chart.setTitle("CPU per edge node — 100% = one core · solid = source, dashed = sink");
        chart.setCreateSymbols(false);
        chart.setAnimated(false);
        javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(4, chart, timeline);
        javafx.scene.layout.VBox.setVgrow(chart, javafx.scene.layout.Priority.ALWAYS);
        getChildren().add(box);
        timeline.setOnWindowChanged(() -> timeline.applyTo(x));

        for (RunRecord r : runs) track(r);
        runs.addListener((ListChangeListener<RunRecord>) c -> {
            while (c.next()) {
                if (c.wasAdded()) c.getAddedSubList().forEach(this::track);
            }
        });
    }

    private void track(RunRecord run) {
        // One line per edge node: the source's agent process and the sink's.
        nodeSeries(run, run.samples, run.sourceName + " (src)", false);
        nodeSeries(run, run.sinkSamples, run.sinkName + " (sink)", true);
    }

    private void nodeSeries(RunRecord run, ObservableList<Telemetry.Sample> samples,
                            String node, boolean dashed) {
        XYChart.Series<Number, Number> s = new XYChart.Series<>();
        s.setName("#" + run.index + " " + node);
        chart.getData().add(s);
        seriesColors.add(run.color);
        style(s, run.color, dashed);

        for (Telemetry.Sample sm : samples) {
            s.getData().add(new XYChart.Data<>(sm.tSecs(), sm.cpuPercent()));
            timeline.extendMax(sm.tSecs());
        }
        samples.addListener((ListChangeListener<Telemetry.Sample>) c -> {
            while (c.next()) {
                if (c.wasAdded()) {
                    for (Telemetry.Sample sm : c.getAddedSubList()) {
                        s.getData().add(new XYChart.Data<>(sm.tSecs(), sm.cpuPercent()));
                        timeline.extendMax(sm.tSecs());
                    }
                }
            }
        });
    }

    private void style(XYChart.Series<Number, Number> s, Color c, boolean dashed) {
        String rgb = LiveChartView.toRgb(c);
        String css = "-fx-stroke: " + rgb + "; -fx-stroke-width: 2px;"
                + (dashed ? " -fx-stroke-dash-array: 6 5;" : "");
        Runnable apply = () -> {
            if (s.getNode() != null) {
                s.getNode().setStyle(css);
            }
            restyleLegend();
        };
        apply.run();
        Platform.runLater(apply);
    }

    /** Legend markers get JavaFX's default palette; recolour them to match the
     *  runs so the legend doesn't contradict the lines. */
    private void restyleLegend() {
        Platform.runLater(() -> {
            var symbols = chart.lookupAll(".chart-legend-item-symbol")
                    .toArray(new javafx.scene.Node[0]);
            for (int i = 0; i < symbols.length && i < seriesColors.size(); i++) {
                symbols[i].setStyle("-fx-background-color: "
                        + LiveChartView.toRgb(seriesColors.get(i))
                        + "; -fx-background-radius: 6px; -fx-padding: 5px;");
            }
        });
    }
}

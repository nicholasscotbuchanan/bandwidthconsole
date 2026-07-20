package com.bwtest.console.ui;

import com.bwtest.console.model.RunRecord;
import com.bwtest.console.model.Telemetry;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;

import java.util.HashMap;
import java.util.Map;

/** Throughput vs. time, one line per run. Because every run stays on the chart,
 *  this doubles as the time-domain comparison view. */
public class LiveChartView extends StackPane {

    private final LineChart<Number, Number> chart;
    private final NumberAxis x;
    private final NumberAxis y;
    private final TimelineSlider timeline = new TimelineSlider();
    private final Map<String, XYChart.Series<Number, Number>> series = new HashMap<>();
    private final Map<String, Color> colors = new HashMap<>();
    /** Every plotted value + its time, for the robust axis ceiling. */
    private final java.util.List<Double> values = new java.util.ArrayList<>();
    private final java.util.List<Double> times = new java.util.ArrayList<>();

    public LiveChartView(ObservableList<RunRecord> runs) {
        x = new NumberAxis();
        x.setLabel("time (s)");
        x.setForceZeroInRange(true);
        y = new NumberAxis();
        y.setLabel("throughput (Mbit/s)");
        y.setForceZeroInRange(true);
        // Manual y range: the first samples of a run count the socket-buffer
        // fill burst; auto-ranging to that spike flattens the steady-state
        // lines. A 95th-percentile ceiling keeps the relevant data readable.
        y.setAutoRanging(false);
        y.setLowerBound(0);

        chart = new LineChart<>(x, y);
        chart.setTitle("Live throughput");
        chart.setCreateSymbols(false);
        chart.setAnimated(false);
        chart.setLegendVisible(true);
        javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(4, chart, timeline);
        javafx.scene.layout.VBox.setVgrow(chart, javafx.scene.layout.Priority.ALWAYS);
        getChildren().add(box);
        timeline.setOnWindowChanged(() -> {
            timeline.applyTo(x);
            rescale();
        });

        for (RunRecord r : runs) track(r);
        runs.addListener((ListChangeListener<RunRecord>) c -> {
            while (c.next()) {
                if (c.wasAdded()) c.getAddedSubList().forEach(this::track);
            }
        });
    }

    private void track(RunRecord run) {
        XYChart.Series<Number, Number> s = new XYChart.Series<>();
        s.setName(run.label());
        series.put(run.id, s);
        colors.put(run.id, run.color);
        chart.getData().add(s);
        styleSeries(s, run.color);

        for (Telemetry.Sample sm : run.samples) {
            s.getData().add(new XYChart.Data<>(sm.tSecs(), sm.mbps()));
            values.add(sm.mbps());
            times.add(sm.tSecs());
            timeline.extendMax(sm.tSecs());
        }
        rescale();
        run.samples.addListener((ListChangeListener<Telemetry.Sample>) c -> {
            while (c.next()) {
                if (c.wasAdded()) {
                    for (Telemetry.Sample sm : c.getAddedSubList()) {
                        s.getData().add(new XYChart.Data<>(sm.tSecs(), sm.mbps()));
                        values.add(sm.mbps());
                        times.add(sm.tSecs());
                        timeline.extendMax(sm.tSecs());
                    }
                }
            }
            rescale();
        });
    }

    /** Ceiling from the values inside the timeline window, so zooming in on a
     *  quiet stretch actually re-scales the y-axis to that stretch. */
    private void rescale() {
        java.util.List<Double> vis = values;
        if (!timeline.isFullView()) {
            vis = new java.util.ArrayList<>();
            for (int i = 0; i < values.size(); i++) {
                double t = times.get(i);
                if (t >= timeline.low() && t <= timeline.high()) vis.add(values.get(i));
            }
            if (vis.isEmpty()) vis = values;
        }
        double upper = Live3DPane.robustMax(vis);
        y.setUpperBound(upper);
        y.setTickUnit(Math.max(1, upper / 6));
    }

    private void styleSeries(XYChart.Series<Number, Number> s, Color c) {
        String rgb = toRgb(c);
        Runnable apply = () -> {
            if (s.getNode() != null) {
                s.getNode().setStyle("-fx-stroke: " + rgb + "; -fx-stroke-width: 2.2px;");
            }
            restyleLegend();
        };
        apply.run();
        javafx.application.Platform.runLater(apply);
    }

    /**
     * Recolour legend markers to match their lines. JavaFX assigns legend symbols
     * from its own default palette, so without this the dots disagree with the
     * series colours — which makes the legend actively misleading.
     */
    private void restyleLegend() {
        javafx.application.Platform.runLater(() -> {
            var symbols = chart.lookupAll(".chart-legend-item-symbol").toArray(new javafx.scene.Node[0]);
            for (int i = 0; i < symbols.length && i < chart.getData().size(); i++) {
                XYChart.Series<Number, Number> s = chart.getData().get(i);
                String id = s.getName();
                Color c = colorFor(id);
                if (c != null) {
                    symbols[i].setStyle("-fx-background-color: " + toRgb(c)
                            + "; -fx-background-radius: 6px; -fx-padding: 5px;");
                }
            }
        });
    }

    private Color colorFor(String seriesName) {
        for (var e : series.entrySet()) {
            if (e.getValue().getName().equals(seriesName)) {
                return colors.get(e.getKey());
            }
        }
        return null;
    }

    static String toRgb(Color c) {
        return String.format("#%02x%02x%02x",
                (int) Math.round(c.getRed() * 255),
                (int) Math.round(c.getGreen() * 255),
                (int) Math.round(c.getBlue() * 255));
    }
}

package com.bwtest.console.ui;

import com.bwtest.console.model.RunRecord;
import com.bwtest.console.model.Telemetry;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.StackedAreaChart;
import javafx.scene.chart.XYChart;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.List;

/**
 * Goodput of each stream in the selected run, stacked: the top edge of the
 * stack is the aggregate, and each band's thickness is that stream's share.
 * A band pinching thin is the tell for unfair scheduling or a stream-level
 * bottleneck that the aggregate chart hides.
 */
public class PerStreamChart extends StackPane {

    private final StackedAreaChart<Number, Number> chart;
    private final NumberAxis x;
    private final NumberAxis y;
    private final TimelineSlider timeline = new TimelineSlider();
    private final ObjectProperty<RunRecord> selected;
    private final List<XYChart.Series<Number, Number>> series = new ArrayList<>();
    /** Stack totals per sample + their times, for the robust axis ceiling. */
    private final List<Double> totals = new ArrayList<>();
    private final List<Double> totalTimes = new ArrayList<>();
    private RunRecord bound;
    private ListChangeListener<Telemetry.Sample> listener;

    public PerStreamChart(ObjectProperty<RunRecord> selected) {
        this.selected = selected;
        x = new NumberAxis();
        x.setLabel("time (s)");
        y = new NumberAxis();
        y.setLabel("stacked per-stream goodput (Mbit/s)");
        y.setForceZeroInRange(true);
        // Robust ceiling — see LiveChartView: don't let the startup buffer-fill
        // spike set the scale for the whole stack.
        y.setAutoRanging(false);
        y.setLowerBound(0);

        chart = new StackedAreaChart<>(x, y);
        chart.setTitle("Per-stream goodput (stacked)");
        chart.setCreateSymbols(false);
        chart.setAnimated(false);
        chart.setLegendVisible(false);
        javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(4, chart, timeline);
        javafx.scene.layout.VBox.setVgrow(chart, javafx.scene.layout.Priority.ALWAYS);
        getChildren().add(box);
        timeline.setOnWindowChanged(() -> {
            timeline.applyTo(x);
            rescale();
        });

        selected.addListener((o, a, b) -> rebind(b));
        rebind(selected.get());
    }

    private void rebind(RunRecord run) {
        if (bound != null && listener != null) {
            bound.samples.removeListener(listener);
        }
        bound = run;
        chart.getData().clear();
        series.clear();
        totals.clear();
        totalTimes.clear();
        timeline.reset();
        if (run == null) return;

        chart.setTitle("Per-stream goodput (stacked) — " + run.label());
        for (Telemetry.Sample s : run.samples) {
            append(s);
        }
        listener = c -> {
            while (c.next()) {
                if (c.wasAdded()) {
                    for (Telemetry.Sample s : c.getAddedSubList()) {
                        append(s);
                    }
                }
            }
        };
        run.samples.addListener(listener);
    }

    /** Add one sample's per-stream values, growing the series set as needed.
     *  Streams that appear mid-run get zero-padded to keep the stack aligned. */
    private void append(Telemetry.Sample s) {
        List<Double> per = s.perStream();
        if (per == null) return;
        while (series.size() < per.size()) {
            XYChart.Series<Number, Number> ser = new XYChart.Series<>();
            ser.setName("stream " + series.size());
            series.add(ser);
            chart.getData().add(ser);
            styleSeries(ser, series.size() - 1);
        }
        double total = 0;
        for (int i = 0; i < series.size(); i++) {
            double v = i < per.size() ? per.get(i) : 0;
            series.get(i).getData().add(new XYChart.Data<>(s.tSecs(), v));
            total += v;
        }
        totals.add(total);
        totalTimes.add(s.tSecs());
        timeline.extendMax(s.tSecs());
        rescale();
    }

    /** Ceiling from the stack totals inside the timeline window. */
    private void rescale() {
        List<Double> vis = totals;
        if (!timeline.isFullView()) {
            vis = new ArrayList<>();
            for (int i = 0; i < totals.size(); i++) {
                double t = totalTimes.get(i);
                if (t >= timeline.low() && t <= timeline.high()) vis.add(totals.get(i));
            }
            if (vis.isEmpty()) vis = totals;
        }
        if (vis.isEmpty()) return;
        double upper = Live3DPane.robustMax(vis);
        y.setUpperBound(upper);
        y.setTickUnit(Math.max(1, upper / 6));
    }

    /** Spread stream colours around the hue wheel so many bands stay legible. */
    private void styleSeries(XYChart.Series<Number, Number> s, int idx) {
        Color c = Color.hsb((idx * 47) % 360, 0.62, 0.98);
        String rgb = LiveChartView.toRgb(c);
        Runnable apply = () -> {
            Node n = s.getNode();
            if (n == null) return;
            Node fill = n.lookup(".chart-series-area-fill");
            Node line = n.lookup(".chart-series-area-line");
            if (fill != null) fill.setStyle("-fx-fill: " + rgba(c, 0.45) + ";");
            if (line != null) line.setStyle("-fx-stroke: " + rgb + "; -fx-stroke-width: 1.2px;");
        };
        apply.run();
        Platform.runLater(apply);
    }

    private static String rgba(Color c, double a) {
        return String.format("rgba(%d,%d,%d,%.2f)",
                (int) (c.getRed() * 255), (int) (c.getGreen() * 255),
                (int) (c.getBlue() * 255), a);
    }
}

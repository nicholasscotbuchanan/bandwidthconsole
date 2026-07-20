package com.bwtest.console.ui;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.chart.NumberAxis;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;

/**
 * Two-thumb timeline scrubber for the time-series charts. Drag a thumb to
 * window the x-axis, drag the highlighted span to pan, double-click to reset
 * to the full range. While the right thumb sits at the live edge the window
 * follows incoming data (sliding if narrowed, growing if at full view).
 */
public class TimelineSlider extends HBox {

    private static final double EPS = 1e-4;

    private final DoubleProperty max = new SimpleDoubleProperty(10);
    private final DoubleProperty low = new SimpleDoubleProperty(0);
    private final DoubleProperty high = new SimpleDoubleProperty(10);
    /** True while the right thumb is glued to the live edge. */
    private boolean followHigh = true;

    private final Track track = new Track();
    private final Label readout = new Label();
    private Runnable onWindow = () -> {};

    public TimelineSlider() {
        setSpacing(10);
        setAlignment(Pos.CENTER);
        readout.getStyleClass().add("mono");
        readout.setStyle("-fx-font-size: 10px; -fx-text-fill: #8a93a6;");
        readout.setMinWidth(Region.USE_PREF_SIZE);
        HBox.setHgrow(track, Priority.ALWAYS);
        getChildren().addAll(track, readout);

        low.addListener((o, a, b) -> changed());
        high.addListener((o, a, b) -> changed());
        max.addListener((o, a, b) -> {
            double oldMax = a.doubleValue(), m = b.doubleValue();
            if (followHigh) {
                boolean full = low.get() <= EPS && high.get() >= oldMax - EPS;
                double width = high.get() - low.get();
                high.set(m);
                if (!full) low.set(Math.max(0, m - width));
            }
            track.requestLayout();
            changed();
        });
        updateReadout();
    }

    /** Callback invoked whenever the visible window (or the extent) changes. */
    public void setOnWindowChanged(Runnable r) { onWindow = r; }

    /** Grow the timeline extent to cover a newly arrived sample time. */
    public void extendMax(double t) {
        if (t > max.get()) max.set(t);
    }

    /** Set the window programmatically (presets, snapshot harness). */
    public void setWindow(double lo, double hi) {
        double l = Track.clamp(lo, 0, max.get() - minGap());
        double h = Track.clamp(hi, l + minGap(), max.get());
        low.set(l);
        high.set(h);
        followHigh = h >= max.get() - EPS;
    }

    /** Forget everything — used when the chart rebinds to a different run. */
    public void reset() {
        followHigh = true;
        max.set(10);
        low.set(0);
        high.set(10);
    }

    public boolean isFullView() {
        return low.get() <= EPS && high.get() >= max.get() - EPS;
    }

    public double low() { return low.get(); }
    public double high() { return high.get(); }

    /** Point the given x-axis at the selected window (or back to auto-range). */
    public void applyTo(NumberAxis x) {
        if (isFullView()) {
            x.setAutoRanging(true);
        } else {
            x.setAutoRanging(false);
            x.setLowerBound(low.get());
            x.setUpperBound(high.get());
            x.setTickUnit(niceTick((high.get() - low.get()) / 8));
        }
    }

    private static double niceTick(double raw) {
        if (raw <= 0) return 1;
        double mag = Math.pow(10, Math.floor(Math.log10(raw)));
        double n = raw / mag;
        double f = n < 1.5 ? 1 : n < 3.5 ? 2 : n < 7.5 ? 5 : 10;
        return f * mag;
    }

    private void changed() {
        updateReadout();
        track.requestLayout();
        onWindow.run();
    }

    private void updateReadout() {
        if (isFullView()) {
            readout.setText(String.format("0–%.0fs · full", max.get()));
        } else {
            readout.setText(String.format("%.1f–%.1fs", low.get(), high.get()));
        }
    }

    private double minGap() { return Math.max(0.2, max.get() * 0.02); }

    /** The draggable rail + span + thumbs. */
    private class Track extends Region {
        static final double PAD = 9;      // half a thumb, so thumbs stay inside
        final Rectangle rail = new Rectangle();
        final Rectangle span = new Rectangle();
        final Circle lo = thumb();
        final Circle hi = thumb();
        int drag;                          // 0 none, 1 low, 2 high, 3 pan
        double panOffset;                  // t distance from window start on grab

        Track() {
            setPrefHeight(22);
            setMinHeight(22);
            rail.setHeight(4);
            rail.setArcWidth(4);
            rail.setArcHeight(4);
            rail.setFill(Color.web("#2a3040"));
            span.setHeight(6);
            span.setArcWidth(6);
            span.setArcHeight(6);
            span.setFill(Color.web("#4f8cff", 0.55));
            getChildren().addAll(rail, span, lo, hi);

            setOnMousePressed(e -> {
                double xl = xFor(low.get()), xh = xFor(high.get());
                if (Math.abs(e.getX() - xl) <= 9 && Math.abs(e.getX() - xl) <= Math.abs(e.getX() - xh)) {
                    drag = 1;
                } else if (Math.abs(e.getX() - xh) <= 9) {
                    drag = 2;
                } else if (e.getX() > xl && e.getX() < xh) {
                    drag = 3;
                    panOffset = tFor(e.getX()) - low.get();
                } else {
                    // Clicked the rail outside the span: jump the nearer thumb.
                    drag = Math.abs(e.getX() - xl) < Math.abs(e.getX() - xh) ? 1 : 2;
                    dragTo(e.getX());
                }
            });
            setOnMouseDragged(e -> dragTo(e.getX()));
            setOnMouseReleased(e -> drag = 0);
            setOnMouseClicked(e -> {
                if (e.getClickCount() == 2) reset();
            });
            setCursor(Cursor.DEFAULT);
        }

        private Circle thumb() {
            Circle c = new Circle(7);
            c.setFill(Color.web("#dfe6f3"));
            c.setStroke(Color.web("#4f8cff"));
            c.setStrokeWidth(2);
            c.setCursor(Cursor.H_RESIZE);
            return c;
        }

        private void dragTo(double px) {
            double t = tFor(px);
            switch (drag) {
                case 1 -> low.set(clamp(t, 0, high.get() - minGap()));
                case 2 -> {
                    high.set(clamp(t, low.get() + minGap(), max.get()));
                    followHigh = high.get() >= max.get() - EPS;
                }
                case 3 -> {
                    double width = high.get() - low.get();
                    double nl = clamp(t - panOffset, 0, max.get() - width);
                    low.set(nl);
                    high.set(nl + width);
                    followHigh = high.get() >= max.get() - EPS;
                }
                default -> { }
            }
        }

        double xFor(double t) {
            double w = Math.max(1, getWidth() - 2 * PAD);
            return PAD + (max.get() <= 0 ? 0 : t / max.get()) * w;
        }

        double tFor(double x) {
            double w = Math.max(1, getWidth() - 2 * PAD);
            return clamp((x - PAD) / w, 0, 1) * max.get();
        }

        static double clamp(double v, double a, double b) {
            return Math.max(a, Math.min(b, v));
        }

        @Override protected void layoutChildren() {
            double cy = getHeight() / 2;
            rail.setX(PAD);
            rail.setY(cy - 2);
            rail.setWidth(Math.max(0, getWidth() - 2 * PAD));
            double xl = xFor(low.get()), xh = xFor(high.get());
            span.setX(xl);
            span.setY(cy - 3);
            span.setWidth(Math.max(0, xh - xl));
            lo.setCenterX(xl);
            lo.setCenterY(cy);
            hi.setCenterX(xh);
            hi.setCenterY(cy);
        }
    }
}

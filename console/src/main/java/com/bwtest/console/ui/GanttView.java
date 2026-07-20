package com.bwtest.console.ui;

import com.bwtest.console.model.RunRecord;
import com.bwtest.console.model.Telemetry;
import javafx.beans.property.ObjectProperty;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.List;

/**
 * The frame lifecycle, as a Gantt.
 *
 * <p>A frame is generated on the sender's disk, read back, put on the wire,
 * received at the receiver, and written out there. The chart's job is to show where
 * that time actually goes — and it draws the five stages as overlapping
 * swimlanes on one wall-clock axis, because that is what really happens: while
 * one frame is in flight the next is being read and the previous is being
 * written. Laying them end to end would be easier to read and would describe a
 * pipeline that does not exist.
 *
 * <p>Two consequences shape the drawing:
 *
 * <ul>
 *   <li><b>It builds as the run happens.</b> Lanes stream in from both agents
 *       while the run is live, so the chart fills in progressively rather than
 *       appearing whole at the end. A lane still running is drawn open-ended.
 *   <li><b>The transmit lane is elided.</b> Transmission dominates every run —
 *       often by two orders of magnitude — so a true-to-scale axis would render
 *       every other stage as a sliver. Instead the chart finds the stretch where
 *       <em>no lane starts or stops</em> and cuts it out, marking the cut with a
 *       torn edge. Only genuinely uneventful time is removed, and the amount is
 *       stated on the axis, so nothing is silently hidden.
 * </ul>
 */
public class GanttView extends Region {

    private record Phase(String name, double ms, Color color) {}

    private final Canvas canvas = new Canvas();
    private final ObjectProperty<RunRecord> selected;

    private static final Color C_CONNECT   = Color.web("#60a5fa");
    private static final Color C_HANDSHAKE = Color.web("#a78bfa");
    private static final Color C_FIRST     = Color.web("#f472b6");
    private static final Color C_RAMP      = Color.web("#fbbf24");
    private static final Color C_STEADY    = Color.web("#34d399");
    private static final Color C_TEARDOWN  = Color.web("#f87171");

    private static final Color BG    = Color.web("#0a1018");
    private static final Color INK   = Color.web("#e2e8f0");
    private static final Color DIM   = Color.web("#94a3b8");
    private static final Color FAINT = Color.web("#64748b");
    private static final Color RULE  = Color.web("#1f2937");
    private static final Color HEAD  = Color.web("#7dd3fc");

    /** Width of the torn-out region, in pixels. */
    private static final double BREAK_PX = 30;
    /** Only elide a quiet stretch if it is at least this share of the run. */
    private static final double BREAK_MIN_SHARE = 0.30;

    public GanttView(ObjectProperty<RunRecord> selected) {
        this.selected = selected;
        getChildren().add(canvas);
        getStyleClass().add("panel");
        widthProperty().addListener((o, a, b) -> redraw());
        heightProperty().addListener((o, a, b) -> redraw());
        selected.addListener((o, a, b) -> {
            hook(b);
            redraw();
        });
        hook(selected.get());
    }

    private RunRecord hooked;
    private void hook(RunRecord r) {
        if (r == hooked) return;
        hooked = r;
        if (r != null) {
            r.summaryProperty().addListener((o, a, b) -> redraw());
            // The whole point of streaming lanes: redraw as they arrive, so the
            // chart tracks the run instead of waiting for it.
            r.lanes.addListener((javafx.collections.MapChangeListener<
                    Telemetry.Lane, Telemetry.LaneUpdate>) c -> redraw());
        }
    }

    @Override
    protected void layoutChildren() {
        canvas.setWidth(getWidth());
        canvas.setHeight(getHeight());
        redraw();
    }

    private void redraw() {
        double w = getWidth(), h = getHeight();
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, w, h);
        if (w < 20 || h < 20) return;

        g.setFill(BG);
        g.fillRect(0, 0, w, h);

        g.setFill(INK);
        g.setFont(Font.font("System", FontWeight.BOLD, 13));
        g.fillText("Frame lifecycle", 16, 24);

        RunRecord r = selected.get();
        if (r == null) {
            g.setFill(FAINT);
            g.setFont(Font.font(12));
            g.fillText("Select a run to see where its time goes.", 16, 52);
            return;
        }

        double left = 150, right = w - 152;
        double y = 56;

        List<Telemetry.LaneUpdate> lanes = r.lifecycleLanes();
        if (!lanes.isEmpty()) {
            y = drawLifecycle(g, r, lanes, left, right, y);
        } else if (r.getSummary() == null) {
            g.setFill(FAINT);
            g.setFont(Font.font(12));
            g.fillText("Waiting for the first frames…", 16, y);
            return;
        }

        Telemetry.Summary s = r.getSummary();
        if (s == null) {
            // Say what is missing and why, rather than leaving the space blank —
            // the lanes above are live, these sections simply cannot exist yet.
            g.setFill(FAINT);
            g.setFont(Font.font(11));
            g.fillText("Run in progress — lanes above update live; connection setup and "
                    + "run totals appear when it finishes.", 16, y + 20);
            return;
        }
        drawLatency(g, s, left, right, y + 18, h);
    }

    // -----------------------------------------------------------------------
    // Lifecycle swimlanes
    // -----------------------------------------------------------------------

    /** Draw the five overlapping lanes; returns the y below the section. */
    private double drawLifecycle(GraphicsContext g, RunRecord rec,
                                 List<Telemetry.LaneUpdate> lanes,
                                 double left, double right, double top) {
        double t0 = Double.MAX_VALUE, t1 = 0;
        for (Telemetry.LaneUpdate u : lanes) {
            t0 = Math.min(t0, u.startMs());
            t1 = Math.max(t1, u.endMs());
        }
        if (t1 <= t0) t1 = t0 + 1;

        // A still-running lane is drawn with a trailing fade and caret past its
        // end; give that room rather than letting it run into the stat column.
        boolean anyRunning = false;
        for (Telemetry.LaneUpdate u : lanes) anyRunning |= !u.complete();
        TimeAxis axis = TimeAxis.of(lanes, t0, t1, left, anyRunning ? right - 28 : right);

        g.setFill(FAINT);
        g.setFont(Font.font(10));
        g.fillText("overlapping stages on one wall clock  ·  bar width = elapsed, "
                + "solid fill = time actually spent working", 16, top - 12);

        double rowH = 28;
        double barH = 16;
        double blockTop = top;
        double blockBottom = top + lanes.size() * rowH;

        // The torn-out column runs behind every lane, so a single break reads as
        // one cut through the whole chart rather than five unrelated gaps.
        if (axis.hasBreak()) {
            g.setFill(BG);
            g.fillRect(axis.breakX(), blockTop - 4, BREAK_PX, blockBottom - blockTop + 8);
        }

        for (int i = 0; i < lanes.size(); i++) {
            Telemetry.LaneUpdate u = lanes.get(i);
            double ry = blockTop + i * rowH;
            double by = ry + (rowH - barH) / 2;
            Color col = colorOf(u.lane());

            g.setFill(u.lane().isReceiveSide() ? DIM : INK);
            g.setFont(Font.font("System", FontWeight.BOLD, 11));
            g.fillText(u.lane().label, 16, by + barH - 4);
            g.setFill(FAINT);
            g.setFont(Font.font(9));
            g.fillText(u.lane().isReceiveSide() ? "recv" : "send", 16, by + barH + 8);

            double x0 = axis.x(u.startMs());
            double x1 = axis.x(u.endMs());

            // Track: the lane's full wall-clock extent, drawn faint. The solid
            // overlay is the share of that extent actually spent working, so a
            // lane that mostly waits on another stage looks hollow rather than
            // busy — which is the distinction the chart exists to make.
            drawBar(g, axis, x0, x1, by, barH, col.deriveColor(0, 1, 1, 0.22));
            double busyW = (x1 - x0) * u.duty();
            drawBar(g, axis, x0, x0 + busyW, by, barH, col);

            if (!u.complete()) {
                // Still running: leave the right edge open rather than capping it,
                // so an in-flight lane never reads as a finished one.
                drawRunningEdge(g, x1, by, barH, col);
            }

            // Right-hand stat: the per-frame cost of this stage, which is the
            // number worth comparing across lanes.
            g.setFill(DIM);
            g.setFont(Font.font(10));
            String stat = u.done() > 0 ? fmt(u.perFrameMs()) + "/frame" : "—";
            g.fillText(stat, right + 14, by + barH - 4);
            g.setFill(FAINT);
            g.setFont(Font.font(9));
            g.fillText(String.format("%,d frames", u.done()), right + 14, by + barH + 8);
        }

        double axisY = blockBottom + 6;
        drawAxis(g, axis, axisY, blockTop, blockBottom);

        return axisY + 34 + drawVerdict(g, lanes, axisY + 34);
    }

    /** A bar clipped around the torn-out column, so nothing spans the cut. */
    private void drawBar(GraphicsContext g, TimeAxis axis, double x0, double x1,
                         double y, double h, Color c) {
        if (x1 <= x0) return;
        g.setFill(c);
        if (!axis.hasBreak()) {
            g.fillRect(x0, y, Math.max(2, x1 - x0), h);
            return;
        }
        double bx0 = axis.breakX(), bx1 = bx0 + BREAK_PX;
        if (x1 <= bx0 || x0 >= bx1) {
            g.fillRect(x0, y, Math.max(2, x1 - x0), h);
            return;
        }
        if (x0 < bx0) {
            g.fillRect(x0, y, bx0 - x0, h);
            tear(g, bx0, y, h, c, true);
        }
        if (x1 > bx1) {
            g.fillRect(bx1, y, x1 - bx1, h);
            tear(g, bx1, y, h, c, false);
        }
    }

    /**
     * The torn edge either side of the cut: a zigzag in the bar's own colour, so
     * it reads as "this bar continues" rather than as a gap in the data.
     */
    private void tear(GraphicsContext g, double x, double y, double h, Color c,
                      boolean pointRight) {
        double amp = 5, step = h / 4;
        double[] xs = new double[6];
        double[] ys = new double[6];
        xs[0] = x; ys[0] = y;
        for (int i = 1; i <= 4; i++) {
            xs[i] = x + (i % 2 == 1 ? (pointRight ? amp : -amp) : 0);
            ys[i] = y + i * step;
        }
        xs[5] = x; ys[5] = y + h;
        g.setFill(c);
        g.fillPolygon(xs, ys, 6);
    }

    /** An open right edge: a soft fade plus a caret, for a lane still running. */
    private void drawRunningEdge(GraphicsContext g, double x, double y, double h, Color c) {
        for (int i = 0; i < 9; i++) {
            g.setFill(c.deriveColor(0, 1, 1, 0.5 * (1 - i / 9.0)));
            g.fillRect(x + i * 2, y, 2, h);
        }
        g.setFill(c);
        g.setFont(Font.font("System", FontWeight.BOLD, 10));
        g.fillText("▸", x + 19, y + h - 4);
    }

    /** Time axis with tick labels, plus the break marker if one is in play. */
    private void drawAxis(GraphicsContext g, TimeAxis axis, double axisY,
                          double blockTop, double blockBottom) {
        g.setStroke(RULE);
        g.setLineWidth(1);
        g.strokeLine(axis.x0, axisY, axis.x1, axisY);

        g.setFill(FAINT);
        g.setFont(Font.font(9));
        g.fillText("0", axis.x0, axisY + 13);
        String end = fmt(axis.t1 - axis.t0);
        g.fillText(end, axis.x1 - end.length() * 5.0, axisY + 13);

        if (!axis.hasBreak()) return;

        // Mark the cut on the axis itself, and say exactly how much time it
        // stands for — an elision the reader cannot quantify is a lie.
        double bx = axis.breakX();
        g.setStroke(Color.web("#334155"));
        g.setLineDashes(3, 3);
        g.strokeLine(bx, blockTop - 4, bx, blockBottom + 4);
        g.strokeLine(bx + BREAK_PX, blockTop - 4, bx + BREAK_PX, blockBottom + 4);
        g.setLineDashes(null);

        String cut = fmt(axis.cutTo - axis.cutFrom) + " elided";
        g.setFont(Font.font("System", FontWeight.BOLD, 9));
        double tw = cut.length() * 4.8;
        g.setFill(BG);
        g.fillRect(bx + BREAK_PX / 2 - tw / 2 - 4, axisY + 4, tw + 8, 13);
        g.setFill(Color.web("#7dd3fc"));
        g.fillText(cut, bx + BREAK_PX / 2 - tw / 2, axisY + 14);
    }

    /**
     * One sentence naming the stage that dominates. The chart shows where the
     * time is; this says what to do about it.
     */
    private double drawVerdict(GraphicsContext g, List<Telemetry.LaneUpdate> lanes, double y) {
        Telemetry.LaneUpdate worst = null;
        double diskMs = 0, wireMs = 0;
        for (Telemetry.LaneUpdate u : lanes) {
            // Generation is staging, not transfer — it is deliberately outside
            // the read/transmit/receive/write comparison.
            if (u.lane() == Telemetry.Lane.GENERATE) continue;
            if (worst == null || u.perFrameMs() > worst.perFrameMs()) worst = u;
            if (u.lane() == Telemetry.Lane.TRANSMIT || u.lane() == Telemetry.Lane.RECEIVE) {
                wireMs += u.perFrameMs();
            } else {
                diskMs += u.perFrameMs();
            }
        }
        if (worst == null || worst.perFrameMs() <= 0) return 0;

        double total = diskMs + wireMs;
        double pct = total > 0 ? 100.0 * diskMs / total : 0;
        g.setFill(Color.web("#cbd5e1"));
        g.setFont(Font.font(11));
        g.fillText(String.format(
                "%s dominates at %s per frame  ·  %.0f%% of per-frame time is filesystem "
                        + "(%s disk vs %s wire) — %s",
                worst.lane().label, fmt(worst.perFrameMs()), pct, fmt(diskMs), fmt(wireMs),
                pct >= 55 ? "storage-bound" : pct <= 25 ? "network-bound" : "mixed"),
                16, y);

        Telemetry.LaneUpdate gen = null;
        for (Telemetry.LaneUpdate u : lanes) {
            if (u.lane() == Telemetry.Lane.GENERATE) gen = u;
        }
        if (gen != null) {
            g.setFill(FAINT);
            g.setFont(Font.font(10));
            g.fillText(String.format(
                    "Staging %,d frames took %s up front and is excluded from throughput — "
                            + "the run measures moving frames, not creating them.",
                    gen.done(), fmt(gen.spanMs())), 16, y + 15);
            return 33;
        }
        return 18;
    }

    private static Color colorOf(Telemetry.Lane l) {
        // Warm = sender storage, cool = wire, violet = receiver storage. The hue
        // family says which side of the transfer a stage lives on.
        return switch (l) {
            case GENERATE -> Color.web("#f59e0b");
            case READ     -> Color.web("#fb923c");
            case TRANSMIT -> Color.web("#38bdf8");
            case RECEIVE  -> Color.web("#22d3ee");
            case WRITE    -> Color.web("#c084fc");
        };
    }

    // -----------------------------------------------------------------------
    // Connection setup + run-level proportions (unchanged in intent)
    // -----------------------------------------------------------------------

    private void drawLatency(GraphicsContext g, Telemetry.Summary s,
                             double left, double right, double top, double h) {
        Telemetry.Phases p = s.phases();
        // Setup phases are milliseconds; steady transfer is seconds. Plotting
        // them on one linear axis makes the setup bars invisible slivers, so
        // setup gets its own scale and the run-level proportion is shown apart.
        Phase[] setup = {
                new Phase("Connect", p.connectMs(), C_CONNECT),
                new Phase("Handshake", p.handshakeMs(), C_HANDSHAKE),
                new Phase("First byte", p.firstByteMs(), C_FIRST),
                new Phase("Ramp / slow-start", p.rampMs(), C_RAMP),
        };
        double setupTotal = 0;
        for (Phase ph : setup) setupTotal += Math.max(0, ph.ms());
        double scale = setupTotal <= 0 ? 1 : setupTotal;
        double grand = setupTotal + Math.max(0, p.steadyMs()) + Math.max(0, p.teardownMs());
        if (grand <= 0) grand = 1;

        double plot = Math.max(40, right - left);
        double rowH = 18;

        g.setFill(HEAD);
        g.setFont(Font.font("System", FontWeight.BOLD, 10));
        g.fillText("CONNECTION SETUP", 16, top - 8);

        double offset = 0;
        for (int i = 0; i < setup.length; i++) {
            Phase ph = setup[i];
            double y = top + i * rowH;
            if (y + rowH > h - 40) break;
            g.setFill(DIM);
            g.setFont(Font.font(11));
            g.fillText(ph.name(), 16, y + rowH / 2 + 4);
            double x0 = left + (offset / scale) * plot;
            double bw = Math.max(3, (Math.max(0, ph.ms()) / scale) * plot);
            g.setFill(ph.color());
            g.fillRect(x0, y + 4, bw, rowH - 8);
            g.setFill(INK);
            g.fillText(fmt(ph.ms()), Math.min(x0 + bw + 8, right + 6), y + rowH / 2 + 4);
            offset += Math.max(0, ph.ms());
        }

        double by = top + setup.length * rowH + 18;
        if (by + 30 > h) return;

        g.setFill(HEAD);
        g.setFont(Font.font("System", FontWeight.BOLD, 10));
        g.fillText("WHOLE RUN", 16, by - 8);
        double bx = left;
        double[] parts = {setupTotal, Math.max(0, p.steadyMs()), Math.max(0, p.teardownMs())};
        Color[] cols = {C_RAMP, C_STEADY, C_TEARDOWN};
        String[] names = {"setup", "steady transfer", "teardown"};
        for (int i = 0; i < parts.length; i++) {
            double bw = (parts[i] / grand) * plot;
            g.setFill(cols[i]);
            g.fillRect(bx, by, Math.max(1, bw), 18);
            if (bw > 70) {
                g.setFill(Color.web("#06232a"));
                g.setFont(Font.font("System", FontWeight.BOLD, 10));
                g.fillText(names[i] + "  " + fmt(parts[i]), bx + 8, by + 13);
            }
            bx += bw;
        }
        g.setFill(FAINT);
        g.setFont(Font.font(10));
        g.fillText(fmt(grand) + " total", right + 6, by + 13);

        g.setFill(DIM);
        g.setFont(Font.font(11));
        g.fillText(String.format(
                        "RTT   p50 %.2f ms    p95 %.2f ms    p99 %.2f ms    ·    retransmits %d",
                        s.p50RttMs(), s.p95RttMs(), s.p99RttMs(), s.retransmits()),
                16, by + 32);

        if (s.hasFrames()) {
            Telemetry.FrameStats fs = s.frame();
            g.setFill(fs.framesDropped() > 0 ? Color.web("#fca5a5") : DIM);
            g.fillText(String.format(
                    "Frames  %,d transferred   ·   %,d dropped (%.1f%%)   ·   "
                            + "fastest %.1f ms   slowest %.1f ms",
                    fs.framesTransferred(), fs.framesDropped(), fs.dropRate() * 100,
                    fs.fastestMs(), fs.slowestMs()), 16, by + 46);
        }
    }

    // -----------------------------------------------------------------------

    /**
     * Maps run time to x, with at most one interval cut out.
     *
     * <p>The cut is chosen as the longest stretch in which no lane starts or
     * ends — the only time that can be removed without hiding an event. If no
     * such stretch is a large enough share of the run, nothing is elided and
     * the axis stays plainly linear.
     */
    private static final class TimeAxis {
        final double t0, t1, x0, x1;
        final double cutFrom, cutTo;
        final double scale;

        private TimeAxis(double t0, double t1, double x0, double x1,
                         double cutFrom, double cutTo) {
            this.t0 = t0; this.t1 = t1; this.x0 = x0; this.x1 = x1;
            this.cutFrom = cutFrom; this.cutTo = cutTo;
            double shown = (t1 - t0) - (cutTo - cutFrom);
            double px = (x1 - x0) - (cutTo > cutFrom ? BREAK_PX : 0);
            this.scale = shown <= 0 ? 0 : px / shown;
        }

        static TimeAxis of(List<Telemetry.LaneUpdate> lanes, double t0, double t1,
                           double x0, double x1) {
            double span = t1 - t0;
            if (span <= 0) return new TimeAxis(t0, t1, x0, x1, 0, 0);

            // Every moment a lane changes state is an event the reader must see,
            // so the only removable time is a stretch between two such moments.
            double[] marks = new double[lanes.size() * 2 + 2];
            int n = 0;
            marks[n++] = t0;
            marks[n++] = t1;
            for (Telemetry.LaneUpdate u : lanes) {
                marks[n++] = u.startMs();
                marks[n++] = u.endMs();
            }
            java.util.Arrays.sort(marks, 0, n);

            double gapFrom = 0, gapTo = 0;
            for (int i = 1; i < n; i++) {
                if (marks[i] - marks[i - 1] > gapTo - gapFrom) {
                    gapFrom = marks[i - 1];
                    gapTo = marks[i];
                }
            }
            double gap = gapTo - gapFrom;
            if (gap / span < BREAK_MIN_SHARE) {
                return new TimeAxis(t0, t1, x0, x1, 0, 0);
            }

            // An elision must never reorder the stages. Lanes crossing the cut
            // all lose the same time, so their comparison survives; a lane
            // entirely outside it loses nothing and would otherwise grow to look
            // longer than the very stage being abbreviated. Staging vs transmit
            // is exactly that pairing, and getting it backwards would turn the
            // chart into a lie in the one place it is most likely to be read.
            double shortestCrossing = Double.MAX_VALUE, longestOutside = 0;
            for (Telemetry.LaneUpdate u : lanes) {
                boolean crosses = u.startMs() < gapTo && u.endMs() > gapFrom;
                if (crosses) {
                    shortestCrossing = Math.min(shortestCrossing, u.spanMs());
                } else {
                    longestOutside = Math.max(longestOutside, u.spanMs());
                }
            }
            double maxCut = shortestCrossing == Double.MAX_VALUE
                    ? gap
                    : shortestCrossing - 1.2 * longestOutside;

            double cut = Math.min(gap * 0.76, maxCut);
            // Below this the cut buys less clarity than the torn edge costs.
            if (cut / span < 0.15) {
                return new TimeAxis(t0, t1, x0, x1, 0, 0);
            }
            // Centre it in the quiet stretch, so the cut sits visibly inside the
            // bars rather than flush against an event.
            double mid = (gapFrom + gapTo) / 2;
            return new TimeAxis(t0, t1, x0, x1, mid - cut / 2, mid + cut / 2);
        }

        boolean hasBreak() {
            return cutTo > cutFrom;
        }

        double breakX() {
            return x0 + (cutFrom - t0) * scale;
        }

        double x(double t) {
            if (!hasBreak() || t <= cutFrom) {
                return x0 + (t - t0) * scale;
            }
            if (t >= cutTo) {
                return x0 + (cutFrom - t0) * scale + BREAK_PX + (t - cutTo) * scale;
            }
            // Inside the cut: pin to the near edge so a bar never appears to
            // start or stop within elided time.
            return x0 + (cutFrom - t0) * scale;
        }
    }

    private static String fmt(double ms) {
        if (ms >= 60_000) return String.format("%.1f min", ms / 60_000);
        if (ms >= 1000) return String.format("%.2f s", ms / 1000);
        if (ms < 1 && ms > 0) return String.format("%.2f ms", ms);
        return String.format("%.1f ms", ms);
    }
}

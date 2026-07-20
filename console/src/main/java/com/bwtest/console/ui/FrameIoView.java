package com.bwtest.console.ui;

import com.bwtest.console.model.RunRecord;
import com.bwtest.console.model.Telemetry;
import javafx.beans.property.ObjectProperty;
import javafx.collections.ListChangeListener;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.List;

/**
 * The multi-file view: how long each frame took, and whether the run kept up.
 *
 * <p>Three panels, in the order the questions get asked:
 * <ol>
 *   <li><b>Frame-time histogram</b> — the distribution, in frametest's own 13
 *       buckets so the numbers are directly comparable to a real frametest run.
 *       An average hides the frames that blew their deadline; this doesn't.</li>
 *   <li><b>Frames per second over time</b>, with the target fps drawn as a
 *       deadline line. Time under the line is time the pipeline was losing.</li>
 *   <li><b>Where each frame's time went</b> — open, transfer, close.</li>
 * </ol>
 */
public class FrameIoView extends Region {

    private final Canvas canvas = new Canvas();
    private final ObjectProperty<RunRecord> selected;

    private static final Color BG      = Color.web("#0a1018");
    private static final Color TEXT    = Color.web("#e2e8f0");
    private static final Color MUTED   = Color.web("#64748b");
    private static final Color LABEL   = Color.web("#94a3b8");
    private static final Color HEAD    = Color.web("#7dd3fc");
    private static final Color GRID    = Color.web("#1f2937");
    private static final Color BAR     = Color.web("#38bdf8");
    private static final Color BAR_BAD = Color.web("#f87171");
    private static final Color FPS     = Color.web("#34d399");
    private static final Color DEADLINE = Color.web("#fbbf24");
    private static final Color C_OPEN  = Color.web("#a78bfa");
    private static final Color C_IO    = Color.web("#fb923c");
    private static final Color C_CLOSE = Color.web("#f472b6");

    /** Frames slower than this many ms are drawn hot — the eye should find them. */
    private static final int SLOW_BUCKET = 9; // the "100 ms" bucket and beyond

    public FrameIoView(ObjectProperty<RunRecord> selected) {
        this.selected = selected;
        getChildren().add(canvas);
        getStyleClass().add("panel");
        widthProperty().addListener((o, a, b) -> redraw());
        heightProperty().addListener((o, a, b) -> redraw());
        selected.addListener((o, a, b) -> { hook(b); redraw(); });
        hook(selected.get());
    }

    private RunRecord hooked;
    private void hook(RunRecord r) {
        if (r == hooked) return;
        hooked = r;
        if (r != null) {
            r.summaryProperty().addListener((o, a, b) -> redraw());
            // Live runs have no summary yet; follow the sample stream so the
            // view fills in as the run progresses rather than staying blank.
            r.samples.addListener((ListChangeListener<Telemetry.Sample>) c -> redraw());
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
        g.setFill(TEXT);
        g.setFont(Font.font("System", FontWeight.BOLD, 13));
        g.fillText("Frame I/O", 16, 24);

        RunRecord r = selected.get();
        if (r == null || !r.scenario.isMultiFile()) {
            drawIntro(g, r != null, w);
            return;
        }

        Telemetry.Summary s = r.getSummary();
        Telemetry.FrameStats fs = s == null ? null : s.frame();

        // The stage panel's height is fixed by its content — a bar plus a
        // five-row table — so it gets its space first and the two charts share
        // what is left. Sizing it proportionally instead clips the table.
        final double STAGE_H = 188;
        final double GAP = 20;
        double y = 48;
        double avail = h - y - 12;

        if (fs == null) {
            drawFpsPanel(g, r, 16, y, w - 32, Math.max(90, avail - 40));
            g.setFill(MUTED);
            g.setFont(Font.font(12));
            g.fillText("Distribution and stage breakdown appear when the run completes.",
                    16, h - 18);
            return;
        }

        double charts = Math.max(160, avail - STAGE_H - GAP * 2);
        y = drawFpsPanel(g, r, 16, y, w - 32, Math.max(70, charts * 0.46));
        y = drawHistogram(g, fs, 16, y + GAP, w - 32, Math.max(70, charts * 0.54 - 46));
        drawStagePanel(g, fs, r, 16, y + GAP, w - 32);
    }

    /**
     * The empty state, which is the first thing most people meet here. It has to
     * do real work: say what the tab measures, name the tool the semantics come
     * from, and give the exact steps to produce data — an empty panel that only
     * says "select a run" leaves the whole feature undiscoverable.
     */
    private void drawIntro(GraphicsContext g, boolean largeFileRunSelected, double w) {
        double x = 16, y = 54;

        g.setFill(LABEL);
        g.setFont(Font.font(12));
        y = wrap(g, largeFileRunSelected
                ? "The selected run sent one continuous stream, so it has no per-frame "
                  + "timings. This tab measures the other case:"
                : "This tab measures what it costs to move thousands of discrete frame "
                  + "files instead of one continuous stream —", x, y, w - 32, 17);
        y = wrap(g, "per-frame open / transfer / close, frame rate against a target "
                + "deadline, and frames dropped for missing it. The semantics are DVS "
                + "frametest's, so the numbers compare directly with it.", x, y, w - 32, 17);

        y += 16;
        g.setFill(HEAD);
        g.setFont(Font.font("System", FontWeight.BOLD, 10));
        g.fillText("TO FILL THIS TAB", x, y);
        y += 20;

        String[] steps = {
                "1.   Set  Sending → \"Multi-file — frametest\"  in the run panel.",
                "2.   Pick a frame size and count — 4K is 51,052,544 B, matching frametest's -w 4k.",
                "3.   Optionally set a target fps and queue depth to enable drop accounting.",
                "4.   Run. Compare against the same scenario in Large file mode to see the cost.",
        };
        g.setFont(Font.font(12));
        for (String s : steps) {
            g.setFill(TEXT);
            g.fillText(s, x + 4, y);
            y += 21;
        }

        y += 14;
        g.setFill(HEAD);
        g.setFont(Font.font("System", FontWeight.BOLD, 10));
        g.fillText("ALSO ON THE COMMAND LINE", x, y);
        y += 20;
        g.setFill(MUTED);
        g.setFont(Font.font(12));
        y = wrap(g, "Existing frametest command lines run as-is against a local path, "
                + "with the original report format:", x, y, w - 32, 17);
        y += 4;
        g.setFill(C_IO);
        g.setFont(Font.font("Menlo", 12));
        g.fillText("bwagent frametest -w 4k -n 3000 -t 4 /mnt/san/TEST", x + 4, y);

        // The Gantt carries the other half of the answer, so point at it.
        y += 30;
        g.setFill(MUTED);
        g.setFont(Font.font(12));
        wrap(g, "The Latency Gantt tab splits each frame into sender disk → network → "
                + "receiver disk, which is where you see whether the storage or the wire is "
                + "costing you the throughput.", x, y, w - 32, 17);
    }

    /** Draw wrapped text, returning the y just past the last line. */
    private double wrap(GraphicsContext g, String text, double x, double y,
                        double maxW, double lineH) {
        // Rough advance for the 12px UI font; exact metrics would need a Text
        // node measure pass, and this only has to avoid running off the panel.
        int perLine = Math.max(20, (int) (maxW / 6.4));
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.length() + word.length() + 1 > perLine) {
                g.fillText(line.toString(), x, y);
                y += lineH;
                line.setLength(0);
            }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        if (line.length() > 0) {
            g.fillText(line.toString(), x, y);
            y += lineH;
        }
        return y;
    }

    /**
     * Frames per second over the run, with the target drawn as a deadline. The
     * gap between the line and the deadline is the story: a run that sits on the
     * line is keeping up, one that dips below it is dropping frames.
     */
    private double drawFpsPanel(GraphicsContext g, RunRecord r,
                                double x, double y, double w, double h) {
        g.setFill(HEAD);
        g.setFont(Font.font("System", FontWeight.BOLD, 10));
        g.fillText("FRAME RATE OVER TIME", x, y);
        double top = y + 10, bottom = top + h;

        List<Telemetry.Sample> samples = r.samples;
        double target = r.scenario.frame == null ? 0 : r.scenario.frame.fpsLimit;
        double maxFps = target;
        double maxT = 0;
        for (Telemetry.Sample sm : samples) {
            if (sm.frame() == null) continue;
            maxFps = Math.max(maxFps, sm.frame().fps());
            maxT = Math.max(maxT, sm.tSecs());
        }
        if (maxFps <= 0) maxFps = 1;
        if (maxT <= 0) maxT = 1;
        maxFps *= 1.15;

        g.setStroke(GRID);
        g.setLineWidth(1);
        g.strokeLine(x, bottom, x + w, bottom);

        // The deadline first, so the series draws over it.
        if (target > 0) {
            double ty = bottom - (target / maxFps) * h;
            g.setStroke(DEADLINE);
            g.setLineDashes(5, 4);
            g.strokeLine(x, ty, x + w, ty);
            g.setLineDashes(null);
            g.setFill(DEADLINE);
            g.setFont(Font.font(10));
            g.fillText(String.format("target %.0f fps", target), x + w - 92, ty - 4);
        }

        // Fill under the curve rather than drawing a bare line: the area is what
        // was actually delivered, so a dip reads as a loss of volume, not just a
        // wiggle. Segments that fall short of the target are filled hot.
        boolean started = false;
        double px = 0, py = 0, pFps = 0;
        for (Telemetry.Sample sm : samples) {
            if (sm.frame() == null) continue;
            double cFps = sm.frame().fps();
            double cx = x + (sm.tSecs() / maxT) * w;
            double cy = bottom - (cFps / maxFps) * h;
            if (started) {
                boolean short_ = target > 0 && (cFps < target * 0.995 || pFps < target * 0.995);
                g.setFill(short_ ? Color.web("#f87171", 0.28) : Color.web("#34d399", 0.20));
                g.fillPolygon(new double[]{px, cx, cx, px},
                              new double[]{py, cy, bottom, bottom}, 4);
                g.setStroke(short_ ? BAR_BAD : FPS);
                g.setLineWidth(1.8);
                g.strokeLine(px, py, cx, cy);
            }
            px = cx; py = cy; pFps = cFps; started = true;
        }
        if (!started) {
            g.setFill(MUTED);
            g.setFont(Font.font(11));
            g.fillText("Waiting for frames…", x + 6, top + h / 2);
        }

        g.setFill(MUTED);
        g.setFont(Font.font(10));
        g.fillText("0", x, bottom + 13);
        g.fillText(String.format("%.0f s", maxT), x + w - 26, bottom + 13);
        g.fillText(String.format("%.0f fps", maxFps), x + 2, top + 9);

        long dropped = r.framesDropped();
        g.setFill(dropped > 0 ? BAR_BAD : LABEL);
        g.setFont(Font.font(11));
        g.fillText(String.format("%,d frames transferred   ·   %,d dropped",
                r.framesTransferred(), dropped), x, bottom + 28);
        return bottom + 32;
    }

    /** frametest's 13-bucket distribution of whole-frame completion times. */
    private double drawHistogram(GraphicsContext g, Telemetry.FrameStats fs,
                                 double x, double y, double w, double h) {
        g.setFill(HEAD);
        g.setFont(Font.font("System", FontWeight.BOLD, 10));
        g.fillText("FRAME COMPLETION TIME DISTRIBUTION", x, y);
        double top = y + 10, bottom = top + h;

        List<Long> hist = fs.histogram();
        int n = Telemetry.FrameStats.BUCKETS.length;
        long max = 1;
        for (Long v : hist) max = Math.max(max, v);

        double cell = w / n;
        for (int i = 0; i < n; i++) {
            long count = i < hist.size() ? hist.get(i) : 0;
            double bh = (count / (double) max) * h;
            g.setFill(i >= SLOW_BUCKET ? BAR_BAD : BAR);
            g.fillRect(x + i * cell + 3, bottom - bh, Math.max(1, cell - 6), bh);

            g.setFill(MUTED);
            g.setFont(Font.font(9));
            String lbl = Telemetry.FrameStats.BUCKETS[i];
            g.fillText(lbl, x + i * cell + (cell - lbl.length() * 5) / 2, bottom + 13);
            if (count > 0) {
                g.setFill(LABEL);
                g.fillText(String.valueOf(count),
                        x + i * cell + (cell - String.valueOf(count).length() * 5) / 2,
                        bottom - bh - 4);
            }
        }
        g.setStroke(GRID);
        g.strokeLine(x, bottom, x + w, bottom);
        g.setFill(MUTED);
        g.setFont(Font.font(10));
        g.fillText("milliseconds per frame", x, bottom + 26);
        return bottom + 30;
    }

    /** Where a frame's time went: open, data transfer, close. */
    private void drawStagePanel(GraphicsContext g, Telemetry.FrameStats fs,
                                RunRecord r, double x, double y, double w) {
        g.setFill(HEAD);
        g.setFont(Font.font("System", FontWeight.BOLD, 10));
        g.fillText("WHERE EACH FRAME'S TIME WENT", x, y);

        double total = fs.create().avgMs() + fs.io().avgMs() + fs.close().avgMs();
        if (total <= 0) total = 1;
        double barY = y + 10;
        double[] parts = {fs.create().avgMs(), fs.io().avgMs(), fs.close().avgMs()};
        Color[] cols = {C_OPEN, C_IO, C_CLOSE};
        String[] names = {"open", "transfer", "close"};
        double bx = x;
        for (int i = 0; i < parts.length; i++) {
            double bw = (parts[i] / total) * w;
            g.setFill(cols[i]);
            g.fillRect(bx, barY, Math.max(1, bw), 22);
            if (bw > 74) {
                g.setFill(Color.web("#06232a"));
                g.setFont(Font.font("System", FontWeight.BOLD, 10));
                g.fillText(String.format("%s  %.2f ms", names[i], parts[i]), bx + 8, barY + 15);
            }
            bx += bw;
        }

        // min/avg/max per stage — the same four blocks frametest prints, because
        // the spread matters more than the mean on shared storage.
        g.setFont(Font.font(11));
        double ty = barY + 44;
        g.setFill(LABEL);
        g.fillText(String.format("%-10s %10s %10s %10s", "", "min", "avg", "max"), x, ty);
        String[] rows = {"frame", "open", "transfer", "close"};
        Telemetry.Stage[] stages = {fs.file(), fs.create(), fs.io(), fs.close()};
        for (int i = 0; i < rows.length; i++) {
            ty += 16;
            g.setFill(i == 0 ? TEXT : LABEL);
            g.fillText(String.format("%-10s %8.3f ms %8.3f ms %8.3f ms",
                    rows[i], stages[i].minMs(), stages[i].avgMs(), stages[i].maxMs()), x, ty);
        }

        // Worst case in the terms that matter to a playback pipeline.
        long frameBytes = r.scenario.frame == null ? 0 : r.scenario.frame.frameBytes;
        if (frameBytes > 0) {
            ty += 24;
            g.setFill(MUTED);
            g.setFont(Font.font(11));
            g.fillText(String.format(
                    "Slowest frame %.1f ms = %.0f MB/s sustained — the floor this "
                            + "pipeline can guarantee.",
                    fs.slowestMs(),
                    Telemetry.FrameStats.rateFor(frameBytes, fs.slowestMs())), x, ty);
        }
    }
}

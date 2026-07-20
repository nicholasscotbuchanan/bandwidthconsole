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

/**
 * Latency waterfall for the selected run: each connection phase drawn as a bar
 * offset by where it happens in the run, so it's obvious whether time is lost to
 * setup, ramp, or steady-state transfer.
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
    // The I/O band's three parts. Disk at each end shares a hue family so the
    // eye reads "storage vs wire" before it reads "source vs sink".
    private static final Color C_SRC_IO    = Color.web("#fb923c");
    private static final Color C_SINK_IO   = Color.web("#fbbf24");
    private static final Color C_NET       = Color.web("#38bdf8");

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
        if (r != null) r.summaryProperty().addListener((o, a, b) -> redraw());
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

        g.setFill(Color.web("#0a1018"));
        g.fillRect(0, 0, w, h);

        g.setFill(Color.web("#e2e8f0"));
        g.setFont(Font.font("System", FontWeight.BOLD, 13));
        g.fillText("Latency breakdown", 16, 24);

        RunRecord r = selected.get();
        Telemetry.Summary s = r == null ? null : r.getSummary();
        if (s == null) {
            g.setFill(Color.web("#64748b"));
            g.setFont(Font.font(12));
            g.fillText(r == null ? "Select a completed run to see its latency waterfall."
                            : "Run in progress — waterfall appears when it completes.",
                    16, 52);
            return;
        }

        Telemetry.Phases p = s.phases();
        // Setup phases are milliseconds; steady transfer is seconds. Plotting them
        // on one linear axis makes the setup bars invisible slivers — which defeats
        // the point of the view. So setup gets its own scale, and the run-level
        // proportion is shown separately underneath.
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

        boolean hasIo = p.hasIoBreakdown();

        double left = 160, right = w - 100;
        double plot = Math.max(40, right - left);
        double top = 52;
        // The I/O band needs vertical room; take it from the setup rows, which
        // stay legible when squeezed in a way a stacked bar would not.
        double reserved = hasIo ? 216 : 130;
        double rowH = Math.min(30, (h - top - reserved) / setup.length);
        double gap = rowH * 0.34;

        g.setFill(Color.web("#7dd3fc"));
        g.setFont(Font.font("System", FontWeight.BOLD, 10));
        g.fillText("CONNECTION SETUP", 16, top - 12);
        g.setFont(Font.font(11));

        double offset = 0;
        for (int i = 0; i < setup.length; i++) {
            Phase ph = setup[i];
            double y = top + i * rowH;
            g.setFill(Color.web("#94a3b8"));
            g.fillText(ph.name(), 16, y + rowH / 2 + 4);
            double x0 = left + (offset / scale) * plot;
            double bw = Math.max(3, (Math.max(0, ph.ms()) / scale) * plot);
            g.setFill(ph.color());
            g.fillRect(x0, y + gap / 2, bw, rowH - gap);
            g.setFill(Color.web("#e2e8f0"));
            g.fillText(fmt(ph.ms()), Math.min(x0 + bw + 8, right + 6), y + rowH / 2 + 4);
            offset += Math.max(0, ph.ms());
        }

        double axisY = top + setup.length * rowH + 4;
        g.setStroke(Color.web("#1f2937"));
        g.strokeLine(left, axisY, right, axisY);
        g.setFill(Color.web("#64748b"));
        g.setFont(Font.font(10));
        g.fillText("0", left, axisY + 14);
        g.fillText(fmt(setupTotal) + " to first byte + ramp", left + plot * 0.55, axisY + 14);

        // Per-frame breakdown: where a single frame's time actually goes. This is
        // the whole reason multi-file mode exists — it answers "is the disk or
        // the wire costing me this?" without needing a second tool.
        //
        // It gets its own scale, for the same reason the setup rows do: a frame
        // is tens of milliseconds where the run is tens of seconds, so sharing an
        // axis would render it as a sliver.
        double afterIo = axisY;
        if (hasIo) {
            double iy = axisY + 34;
            g.setFill(Color.web("#7dd3fc"));
            g.setFont(Font.font("System", FontWeight.BOLD, 10));
            g.fillText("PER FRAME", 16, iy - 8);

            double frameMs = p.perFrameTotal();
            double[] parts = {p.srcIoMs(), p.netMs(), p.sinkIoMs()};
            Color[] cols = {C_SRC_IO, C_NET, C_SINK_IO};
            String[] names = {"source disk", "network", "sink disk"};
            double bx = left;
            for (int i = 0; i < parts.length; i++) {
                double bw = (Math.max(0, parts[i]) / frameMs) * plot;
                g.setFill(cols[i]);
                g.fillRect(bx, iy, Math.max(1, bw), 24);
                if (bw > 78) {
                    g.setFill(Color.web("#06232a"));
                    g.setFont(Font.font("System", FontWeight.BOLD, 10));
                    g.fillText(names[i] + "  " + fmt(parts[i]), bx + 8, iy + 16);
                }
                bx += bw;
            }
            g.setFill(Color.web("#94a3b8"));
            g.setFont(Font.font(11));
            g.fillText(fmt(frameMs) + " / frame", right + 6, iy + 16);

            // The headline: what share of a frame is storage rather than wire.
            double io = p.srcIoMs() + p.sinkIoMs();
            double pct = frameMs > 0 ? 100.0 * io / frameMs : 0;
            g.setFill(Color.web("#cbd5e1"));
            g.setFont(Font.font(11));
            g.fillText(String.format(
                    "%.0f%% of each frame is filesystem I/O (%s disk vs %s wire) — %s",
                    pct, fmt(io), fmt(p.netMs()),
                    pct >= 55 ? "storage-bound" : pct <= 25 ? "network-bound" : "mixed"),
                    16, iy + 44);
            afterIo = iy + 54;
        }

        // Whole-run proportion: one stacked bar, so the (usually dominant) steady
        // phase is visible as context without crushing the detail above.
        double by = afterIo + 40;
        g.setFill(Color.web("#7dd3fc"));
        g.setFont(Font.font("System", FontWeight.BOLD, 10));
        g.fillText("WHOLE RUN", 16, by - 8);
        double bx = left;
        double[] parts = {setupTotal, Math.max(0, p.steadyMs()), Math.max(0, p.teardownMs())};
        Color[] cols = {C_RAMP, C_STEADY, C_TEARDOWN};
        String[] names = {"setup", "steady transfer", "teardown"};
        for (int i = 0; i < parts.length; i++) {
            double bw = (parts[i] / grand) * plot;
            g.setFill(cols[i]);
            g.fillRect(bx, by, Math.max(1, bw), 22);
            if (bw > 70) {
                g.setFill(Color.web("#06232a"));
                g.setFont(Font.font("System", FontWeight.BOLD, 10));
                g.fillText(names[i] + "  " + fmt(parts[i]), bx + 8, by + 15);
            }
            bx += bw;
        }
        g.setFill(Color.web("#64748b"));
        g.setFont(Font.font(10));
        g.fillText(fmt(grand) + " total", right + 6, by + 15);

        // Tail latencies summary
        g.setFill(Color.web("#94a3b8"));
        g.setFont(Font.font(11));
        g.fillText(String.format("RTT   p50 %.2f ms    p95 %.2f ms    p99 %.2f ms    ·    retransmits %d",
                        s.p50RttMs(), s.p95RttMs(), s.p99RttMs(), s.retransmits()),
                16, by + 52);

        if (s.hasFrames()) {
            Telemetry.FrameStats fs = s.frame();
            g.setFill(fs.framesDropped() > 0 ? Color.web("#fca5a5") : Color.web("#94a3b8"));
            g.fillText(String.format(
                    "Frames  %,d transferred   ·   %,d dropped (%.1f%%)   ·   "
                            + "fastest %.1f ms   slowest %.1f ms",
                    fs.framesTransferred(), fs.framesDropped(), fs.dropRate() * 100,
                    fs.fastestMs(), fs.slowestMs()), 16, by + 70);
        }
    }

    private static String fmt(double ms) {
        if (ms >= 1000) return String.format("%.2f s", ms / 1000);
        return String.format("%.1f ms", ms);
    }
}

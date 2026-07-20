package com.bwtest.console.ui;

import com.bwtest.console.Orchestrator;
import com.bwtest.console.model.*;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Development harness: renders the real console UI with representative data and
 * writes a PNG per tab, so the layout can actually be looked at and iterated on
 * instead of designed blind.
 *
 *   mvn -o javafx:run -Djavafx.mainClass=com.bwtest.console.ui.UiSnapshot
 *
 * Output goes to /tmp/ui-*.png.
 */
public class UiSnapshot extends Application {

    private static final String OUT = "/tmp/";
    private ConsoleUI ui;

    @Override
    public void start(Stage stage) throws Exception {
        Orchestrator orch = new Orchestrator();
        // BW_UI_EMPTY renders the cold-start console — no agents, no runs. That
        // is what a user actually meets first, and every empty state has to earn
        // its place there, so it needs looking at as much as the seeded view.
        if (System.getenv("BW_UI_EMPTY") == null) seed(orch);

        ObjectProperty<RunRecord> selected = new SimpleObjectProperty<>();
        ConsoleUI ui = new ConsoleUI(orch, 9077, selected);
        this.ui = ui;

        Scene scene = new Scene(ui.root(), 1500, 940);
        scene.getStylesheets().add(
                getClass().getResource("/com/bwtest/console/app.css").toExternalForm());
        stage.setScene(scene);
        stage.setTitle("Bandwidth Console (snapshot)");
        stage.show();

        if (!orch.runs.isEmpty()) {
            selected.set(orch.runs.get(orch.runs.size() - 1));
        }

        // Snapshot each tab in turn, giving the scene a beat to lay out and render.
        List<String> names = new ArrayList<>();
        for (Tab t : ui.tabs().getTabs()) {
            names.add(t.getText().toLowerCase().replaceAll("[^a-z0-9]+", "-"));
        }
        shootSequence(scene, ui.tabs(), names, 0);
    }

    private void shootSequence(Scene scene, TabPane tabs, List<String> names, int idx) {
        if (idx >= tabs.getTabs().size()) {
            // Chained, not fired-and-forgotten: the control-panel shot needs a
            // layout pass after switching mode, and an unchained pause would race
            // the exit below and silently produce no file.
            shootControls(scene, () -> {
                shootZoomed(scene, tabs);
                shootSplash();
                PauseTransition done = new PauseTransition(Duration.millis(200));
                done.setOnFinished(e -> {
                    System.out.println("SNAPSHOTS_DONE");
                    Platform.exit();
                });
                done.play();
            });
            return;
        }
        tabs.getSelectionModel().select(idx);
        PauseTransition p = new PauseTransition(Duration.millis(700));
        p.setOnFinished(e -> {
            write(scene, OUT + "ui-" + idx + "-" + names.get(idx) + ".png");
            shootSequence(scene, tabs, names, idx + 1);
        });
        p.play();
    }

    /**
     * Switch the control panel into multi-file mode and expand Advanced, so the
     * frametest controls and the fan-out picker get looked at rather than
     * shipped unseen. The default large-file layout is already covered by every
     * other shot.
     */
    private void shootControls(Scene scene, Runnable next) {
        if (ui == null) { next.run(); return; }
        ui.controls().selectTransferMode(TransferMode.MULTI_FILE);
        ui.controls().setFanoutVisible(true);
        PauseTransition p = new PauseTransition(Duration.millis(600));
        p.setOnFinished(e -> {
            write(scene, OUT + "ui-controls-multifile.png");
            next.run();
        });
        p.play();
    }

    /** Narrow the Live Throughput timeline to 3–7 s and snapshot it, so the
     *  zoomed state of the scrubber gets looked at, not just the full view. */
    private void shootZoomed(Scene scene, TabPane tabs) {
        tabs.getSelectionModel().select(2);
        for (javafx.scene.Node n : scene.getRoot().lookupAll("*")) {
            if (n instanceof TimelineSlider ts && ts.getScene() != null
                    && tabs.getSelectionModel().getSelectedItem().getContent()
                           .lookupAll("*").contains(ts)) {
                ts.setWindow(3, 7);
            }
        }
        write(scene, OUT + "ui-zoomed-live-throughput.png");
    }

    /** Render the startup loader scene too, so its layout gets looked at. */
    private void shootSplash() {
        javafx.scene.control.Label status =
                new javafx.scene.control.Label("binding control port tcp/9077…");
        write(com.bwtest.console.App.splashScene(status), OUT + "ui-splash.png");
    }

    /** Snapshot to PNG. Pixels are copied by hand rather than via SwingFXUtils so
     *  the console doesn't need the javafx-swing module just for a dev tool. */
    private void write(Scene scene, String path) {
        try {
            WritableImage img = scene.snapshot(null);
            int w = (int) img.getWidth(), h = (int) img.getHeight();
            java.awt.image.BufferedImage bi =
                    new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            javafx.scene.image.PixelReader pr = img.getPixelReader();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    bi.setRGB(x, y, pr.getArgb(x, y));
                }
            }
            ImageIO.write(bi, "png", new File(path));
            System.out.println("WROTE " + path);
        } catch (Exception e) {
            System.out.println("FAILED " + path + ": " + e);
        }
    }

    /** Representative agents and completed runs, so the UI is seen with content. */
    private void seed(Orchestrator orch) {
        orch.agents.addAll(
                agent("a1", "edge-nyc", "linux", "x86_64", "10.0.4.11", true, true, true, 32),
                agent("a2", "edge-lon", "linux", "aarch64", "10.0.9.22", false, true, true, 16),
                agent("a3", "laptop-mac", "macos", "aarch64", "192.168.1.7", false, true, true, 10));

        Object[][] specs = {
                {Protocol.TCP, Architecture.SELECTOR, 8, 1, 94000.0, false},
                {Protocol.TCP, Architecture.THREADED, 8, 1, 65000.0, false},
                {Protocol.TCP_SACK, Architecture.SELECTOR, 16, 2, 71000.0, true},
                {Protocol.QUIC, Architecture.SELECTOR, 4, 1, 2900.0, true},
                {Protocol.QUIC_DPDK, Architecture.SELECTOR, 4, 1, 8400.0, true},
        };
        int i = 1;
        for (Object[] s : specs) {
            Scenario sc = Scenario.of((Protocol) s[0], (Architecture) s[1],
                    (int) s[2], (int) s[3], 46, true, 32768, 0, 10).withTls((boolean) s[5]);
            RunRecord r = new RunRecord("run-" + i, i, sc, "edge-nyc", "edge-lon",
                    javafx.scene.paint.Color.web(new String[]{
                            "#5eead4", "#a78bfa", "#f472b6", "#facc15", "#60a5fa"}[i - 1]));
            double peak = (double) s[4];
            int streams = (int) s[2];
            for (int t = 0; t < 40; t++) {
                double f = 0.72 + 0.28 * Math.sin(t / 5.0) * Math.cos(t / 11.0);
                double mbps = peak * Math.max(0.35, f);
                // Reproduce the real agents' startup artifact: the first
                // interval counts the socket-buffer fill and reads ~3× line
                // rate. The views must not let it set the scale.
                if (t < 3) mbps *= 3.0 - t * 0.7;
                // Uneven per-stream split so the contribution view has texture.
                List<Double> per = new ArrayList<>();
                double wsum = 0;
                for (int k = 0; k < streams; k++) {
                    wsum += 1.0 + 0.5 * Math.sin(k * 1.7 + t / 6.0);
                }
                for (int k = 0; k < streams; k++) {
                    per.add(mbps * (1.0 + 0.5 * Math.sin(k * 1.7 + t / 6.0)) / wsum);
                }
                r.samples.add(new Telemetry.Sample(r.id, "source", t * 0.25, mbps, mbps * 90,
                        0.4 + 0.9 * Math.abs(Math.sin(t / 7.0)), t * 3L,
                        80 + 60 * Math.abs(Math.cos(t / 9.0)), per, null));
                // Sink sees slightly less than was offered — the balance gap.
                double eff = System.getenv("BW_UI_EFF") != null
                        ? Double.parseDouble(System.getenv("BW_UI_EFF"))
                        : 0.995 - 0.06 * Math.abs(Math.sin(t / 4.0));
                r.sinkSamples.add(new Telemetry.Sample(r.id, "sink", t * 0.25, mbps * eff,
                        mbps * 90 * eff, 0, 0, 22, List.of(), null));
            }
            r.setSummary(new Telemetry.Summary(r.id, peak * 0.86, peak, (long) (peak * 1e6),
                    0.42, 1.28, 2.10, 37, true,
                    new Telemetry.Phases(1.2, 2.4, 0.8, 18.0, 9800.0, 0.4, 0, 0, 0), null));
            // Leave the last run "live" so the Live 3D views render as in a test.
            r.setState(i == specs.length ? RunRecord.State.RUNNING : RunRecord.State.DONE);
            orch.runs.add(r);
            i++;
        }
        seedFrameRun(orch, i);
    }

    /**
     * A multi-file run, so the Frame I/O tab and the Gantt's I/O band render with
     * content. Shaped like a real 4K frame sequence that <em>nearly</em> keeps up:
     * a 24 fps target that mostly holds but drops a handful of frames, with most
     * of the per-frame time going to disk rather than the wire.
     */
    private void seedFrameRun(Orchestrator orch, int idx) {
        FrameSpec f = new FrameSpec();
        f.mode = FrameMode.READ.wire;
        f.frameBytes = FrameSpec.Preset.FOUR_K.frameBytes();
        f.frameCount = 720;
        f.fpsLimit = 24;
        f.queueDepth = 10;
        f.path = "/mnt/san/DPX-172";
        Scenario sc = Scenario.of(Protocol.TCP, Architecture.THREADED, 4, 1,
                46, true, 32768, 0, 30).withFrames(f);

        RunRecord r = new RunRecord("run-frames", idx, sc, "edge-nyc", "edge-lon",
                javafx.scene.paint.Color.web("#fb923c"));

        long done = 0, dropped = 0;
        List<Long> hist = new ArrayList<>();
        for (int b = 0; b < 13; b++) hist.add(0L);
        double peakMbps = 0;
        for (int t = 0; t < 40; t++) {
            // Frame time wanders around the 41.7 ms budget a 24 fps target allows;
            // when it exceeds that the queue backs up and frames start dropping.
            double frameMs = 34 + 14 * Math.sin(t / 6.0) + 6 * Math.cos(t / 2.3);
            double fps = Math.min(24, 1000.0 / frameMs);
            done += Math.round(fps * 0.75);
            if (frameMs > 41.7) dropped += 1;
            double openMs = 0.18 + 0.06 * Math.abs(Math.sin(t / 3.0));
            double ioMs = frameMs * 0.62;
            double closeMs = frameMs * 0.11;
            hist.set(bucketOf(frameMs), hist.get(bucketOf(frameMs)) + 18);

            double mbps = fps * f.payloadBytes() * 8 / 1.0e6;
            peakMbps = Math.max(peakMbps, mbps);
            Telemetry.FrameProgress fp = new Telemetry.FrameProgress(
                    fps, done, dropped, frameMs, openMs, ioMs, closeMs);
            r.samples.add(new Telemetry.Sample(r.id, "source", t * 0.75, mbps, fps,
                    0.6, 0, 140 + 30 * Math.sin(t / 5.0), List.of(), fp));
            r.sinkSamples.add(new Telemetry.Sample(r.id, "sink", t * 0.75, mbps * 0.98,
                    fps, 0, 0, 60, List.of(),
                    new Telemetry.FrameProgress(fps, done, 0, frameMs * 0.4,
                            0.2, frameMs * 0.3, frameMs * 0.08)));
        }

        Telemetry.FrameStats stats = new Telemetry.FrameStats(
                done, dropped, done * f.payloadBytes(), 21.4, 96.8,
                new Telemetry.Stage(21.4, 38.9, 96.8),   // whole frame
                new Telemetry.Stage(0.09, 0.21, 1.04),   // create
                new Telemetry.Stage(14.1, 24.1, 61.2),   // read
                new Telemetry.Stage(0.31, 4.28, 22.7),   // close
                hist, List.of(
                        new Telemetry.Window("1s", 0.21, 24.1, 38.9, 476.2, 23.1),
                        new Telemetry.Window("5s", 0.20, 23.6, 37.4, 489.9, 23.6),
                        new Telemetry.Window("30s", 0.21, 24.0, 38.2, 481.0, 23.4),
                        new Telemetry.Window("Overall", 0.21, 24.1, 38.9, 478.5, 23.2)));

        // The point of the I/O band: 28.6 ms of the 38.9 ms frame is filesystem
        // work at the two ends, so this run is disk-bound, not network-bound.
        r.setSummary(new Telemetry.Summary(r.id, peakMbps * 0.94, peakMbps,
                done * f.payloadBytes(), 0.58, 1.44, 2.31, 12, true,
                new Telemetry.Phases(1.1, 2.2, 0.7, 0, 30000, 0.5, 28.6, 6.1, 4.2),
                stats));
        r.setState(RunRecord.State.DONE);
        orch.runs.add(r);
    }

    /** Mirrors the agent's histogram bucketing so seeded data lands correctly. */
    private static int bucketOf(double ms) {
        double[] edges = {0.1, 0.2, 0.5, 1, 2, 5, 10, 20, 50, 100, 200, 500};
        for (int i = 0; i < edges.length; i++) if (ms < edges[i]) return i;
        return edges.length;
    }

    private AgentModel agent(String id, String name, String os, String arch, String addr,
                             boolean dpdk, boolean dscp, boolean sack, int cpus) {
        AgentModel a = new AgentModel(id);
        a.setName(name);
        a.setOs(os);
        a.setArch(arch);
        a.setDataAddr(addr);
        a.caps = new Capabilities(dpdk, dscp, sack, cpus * 64, cpus);
        a.setOnline(true);
        return a;
    }

    public static void main(String[] args) {
        launch(args);
    }
}

package com.bwtest.console.ui;

import com.bwtest.console.model.FrameMode;
import com.bwtest.console.model.FrameSpec;
import com.bwtest.console.model.FrameStorage;
import com.bwtest.console.model.RunRecord;
import com.bwtest.console.model.Scenario;
import com.bwtest.console.model.Telemetry;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.collections.MapChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * Always-visible status strip for whatever run is currently working.
 *
 * <p>It exists because a run has long stretches where nothing else in the UI
 * moves. Staging a frame set writes the whole payload to disk before a single
 * byte is transmitted, and during that window there is no telemetry to plot —
 * so every chart sits empty and the only thing twitching is the CPU meter. That
 * reads as a hung application. This strip names the current phase, shows how far
 * through it the run is, and says how long is left.
 *
 * <p>It follows the newest run that is still working rather than the selected
 * one: having started a job, what you want to see is that job's progress, not
 * whichever row happens to be highlighted.
 */
public class RunStatusBar extends HBox {

    private final Label phase = new Label();
    private final ProgressBar bar = new ProgressBar(0);
    private final Label detail = new Label();
    private final Label pct = new Label();

    private final ObservableList<RunRecord> runs;
    private RunRecord tracked;

    // Held so they can be detached when the tracked run changes; otherwise every
    // completed run would keep repainting a bar that no longer describes it.
    private final ChangeListener<RunRecord.State> stateL = (o, a, b) -> refresh();
    private final ListChangeListener<Telemetry.Sample> samplesL = c -> refresh();
    private final MapChangeListener<Telemetry.Lane, Telemetry.LaneUpdate> lanesL = c -> refresh();

    public RunStatusBar(ObservableList<RunRecord> runs) {
        this.runs = runs;
        setSpacing(12);
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(8, 14, 8, 14));
        getStyleClass().add("status-bar");

        phase.getStyleClass().add("status-phase");
        detail.getStyleClass().addAll("hint", "mono");
        pct.getStyleClass().addAll("value-pill", "mono");
        bar.setPrefWidth(260);
        bar.setMinWidth(160);
        bar.setPrefHeight(12);
        bar.setMinHeight(12);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        getChildren().addAll(phase, bar, pct, detail, spacer);

        runs.addListener((ListChangeListener<RunRecord>) c -> retrack());
        retrack();
    }

    /** Point at the newest working run, falling back to the newest run at all. */
    private void retrack() {
        RunRecord next = null;
        for (RunRecord r : runs) {
            if (r.getState() == RunRecord.State.PREPARING
                    || r.getState() == RunRecord.State.RUNNING) {
                next = r;
            }
        }
        if (next == null && !runs.isEmpty()) next = runs.get(runs.size() - 1);
        if (next == tracked) {
            refresh();
            return;
        }
        if (tracked != null) {
            tracked.stateProperty().removeListener(stateL);
            tracked.samples.removeListener(samplesL);
            tracked.lanes.removeListener(lanesL);
        }
        tracked = next;
        if (tracked != null) {
            tracked.stateProperty().addListener(stateL);
            tracked.samples.addListener(samplesL);
            tracked.lanes.addListener(lanesL);
            // A run reaching a terminal state means some other run may now be
            // the one worth watching.
            tracked.stateProperty().addListener((o, a, b) -> Platform.runLater(this::retrack));
        }
        refresh();
    }

    private void refresh() {
        if (tracked == null) {
            show("Idle", -1, "", "No runs yet — configure a test and press Run.");
            bar.setProgress(0);
            return;
        }
        switch (tracked.getState()) {
            case PREPARING -> preparing();
            case RUNNING -> running();
            case DONE -> done();
            case ERROR -> show("Failed", 0, "", tracked.messageProperty().get());
            case ABORTED -> show("Aborted", 0, "", "Run stopped before it completed.");
        }
    }

    /**
     * The window before the sender has said anything.
     *
     * <p>A run that will stage files still knows, locally, exactly how many it
     * is about to write and how big they are — the scenario says so. Showing
     * that here rather than an unlabelled bounce means the one question worth
     * asking during the wait ("how much work is this?") is already answered
     * when the first progress tick arrives.
     */
    private void preparing() {
        if (!willStage()) {
            show("Preparing", -1, "", "Binding the receiver and negotiating the data plane…");
            return;
        }
        show("Preparing", 0, "0%",
                String.format("0 / %,d files staged  ·  %s to write  ·  waiting on receiver…",
                        stageTotal(), Scenario.humanBytes(stageBytes())));
    }

    /**
     * Staging first, transfer second. They are reported separately because they
     * are separate costs: staging writes the frame set to the sender's disk and
     * is deliberately excluded from throughput, so folding it into one "percent
     * complete" would misreport both.
     */
    private void running() {
        Telemetry.LaneUpdate gen = tracked.lanes.get(Telemetry.Lane.GENERATE);
        if (gen != null && !gen.complete()) {
            staging(gen.done(), gen.total(), gen.spanMs() / 1000.0);
            return;
        }
        // Staging has been asked for but the sender's first phase update has not
        // landed yet. Hold the same bar at zero rather than flicking to a
        // "Transferring · 0s elapsed" that is not true yet.
        if (gen == null && willStage() && latestFrame() == null) {
            staging(0, stageTotal(), 0);
            return;
        }

        // Transferring. Frame runs know exactly how many frames are left; a
        // large-file run only knows its byte or time budget.
        Telemetry.FrameProgress fp = latestFrame();
        if (fp != null && tracked.scenario.frame != null) {
            long total = tracked.scenario.frame.totalFrames();
            long done = fp.framesDone() + fp.framesDropped();
            double f = total <= 0 ? -1 : Math.min(1.0, (double) done / total);
            String drops = fp.framesDropped() > 0
                    ? String.format("  ·  %,d dropped", fp.framesDropped()) : "";
            show("Transferring", f, pctText(f),
                    String.format("%,d / %,d frames  ·  %.1f fps%s", done, total, fp.fps(), drops));
            return;
        }

        double t = tracked.samples.isEmpty() ? 0
                : tracked.samples.get(tracked.samples.size() - 1).tSecs();
        double mbps = tracked.samples.isEmpty() ? 0
                : tracked.samples.get(tracked.samples.size() - 1).mbps();
        int dur = tracked.scenario.durationSecs;
        double f = (tracked.scenario.continuous || dur <= 0) ? -1 : Math.min(1.0, t / dur);
        show("Transferring", f, f < 0 ? "" : pctText(f),
                dur > 0 && !tracked.scenario.continuous
                        ? String.format("%.0fs / %ds  ·  %s", t, dur, mbpsText(mbps))
                        : String.format("%.0fs elapsed  ·  %s", t, mbpsText(mbps)));
    }

    private void done() {
        Telemetry.Summary s = tracked.getSummary();
        if (s == null) {
            show("Complete", 1, "100%", "");
            return;
        }
        String frames = s.hasFrames()
                ? String.format("  ·  %,d frames, %,d dropped",
                        s.frame().framesTransferred(), s.frame().framesDropped())
                : "";
        show("Complete", 1, "100%",
                String.format("avg %s  ·  peak %s%s",
                        mbpsText(s.avgMbps()), mbpsText(s.peakMbps()), frames));
    }

    /**
     * The staging bar: files done against files needed, plus what that is
     * costing and how long is left.
     *
     * <p>The estimate is a flat extrapolation of the frames written so far.
     * Staging writes identical files sequentially, so the per-file cost is
     * close to constant and the naive projection is honest; it is withheld
     * until a quarter-second of real work has accumulated, because a rate
     * derived from one or two files swings wildly enough to be misleading.
     */
    private void staging(long done, long total, double secs) {
        long frameBytes = tracked.scenario.frame == null ? 0 : tracked.scenario.frame.frameBytes;
        double f = total <= 0 ? -1 : Math.min(1.0, (double) done / total);

        String bytes = frameBytes > 0
                ? String.format("  ·  %s / %s",
                        Scenario.humanBytes(done * frameBytes), Scenario.humanBytes(total * frameBytes))
                : "";
        String rate = "";
        String eta = "  ·  estimating…";
        if (secs > 0.25 && done > 0) {
            if (frameBytes > 0) {
                rate = String.format("  ·  %.0f MB/s", done * frameBytes / (1024.0 * 1024.0) / secs);
            }
            long left = Math.max(0, total - done);
            eta = left > 0 ? "  ·  " + fmtEta(left * (secs / done)) + " left" : "";
        }
        show("Staging files", f, pctText(f),
                String.format("%,d / %,d files written to sender disk%s%s%s",
                        done, total, bytes, rate, eta));
    }

    /**
     * Whether this run will stage a frame set before transmitting.
     *
     * <p>Mirrors the agent's {@code FramePlan::needs_staging}: only a multi-file
     * write to disk creates files up front. A read run points at frames that
     * already exist and a memory run never touches a filesystem, so promising
     * either of them a file count would be inventing work that never happens.
     */
    private boolean willStage() {
        if (tracked.scenario == null || !tracked.scenario.isMultiFile()) return false;
        FrameSpec fs = tracked.scenario.frame;
        return fs.modeEnum() == FrameMode.WRITE && fs.storageEnum() == FrameStorage.DISK;
    }

    /** Files this run intends to create, known locally from the scenario. */
    private long stageTotal() {
        return tracked.scenario.frame == null ? 0 : tracked.scenario.frame.totalFrames();
    }

    /** Bytes those files add up to, headers included — staging writes whole files. */
    private long stageBytes() {
        FrameSpec fs = tracked.scenario.frame;
        return fs == null ? 0 : fs.totalFrames() * fs.frameBytes;
    }

    /** Newest sample carrying frame progress, or null on a large-file run. */
    private Telemetry.FrameProgress latestFrame() {
        for (int i = tracked.samples.size() - 1; i >= 0; i--) {
            Telemetry.FrameProgress f = tracked.samples.get(i).frame();
            if (f != null) return f;
        }
        return null;
    }

    /** {@code fraction < 0} means "working, but the end is not knowable yet". */
    private void show(String phaseText, double fraction, String pctText, String detailText) {
        phase.setText(phaseText);
        if (fraction < 0) {
            bar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        } else {
            bar.setProgress(fraction);
        }
        pct.setText(pctText);
        pct.setVisible(!pctText.isEmpty());
        pct.setManaged(!pctText.isEmpty());
        detail.setText(detailText == null ? "" : detailText);
    }

    private static String pctText(double f) {
        return f < 0 ? "" : String.format("%.0f%%", f * 100);
    }

    private static String mbpsText(double mbps) {
        return mbps >= 1000 ? String.format("%.1f Gbps", mbps / 1000)
                            : String.format("%.0f Mbps", mbps);
    }

    private static String fmtEta(double secs) {
        if (secs >= 3600) return String.format("%.1f h", secs / 3600);
        if (secs >= 90) return String.format("%.0f min", secs / 60);
        return String.format("%.0f s", secs);
    }
}

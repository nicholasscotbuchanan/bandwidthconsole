package com.bwtest.console.ui;

import com.bwtest.console.Orchestrator;
import com.bwtest.console.model.AgentModel;
import com.bwtest.console.model.Architecture;
import com.bwtest.console.model.Dscp;
import com.bwtest.console.model.FrameMode;
import com.bwtest.console.model.FrameOrder;
import com.bwtest.console.model.FrameSpec;
import com.bwtest.console.model.FrameStorage;
import com.bwtest.console.model.Protocol;
import com.bwtest.console.model.Scenario;
import com.bwtest.console.model.TransferMode;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The run cockpit. Designed around one question — "how do I start a test?" — so
 * the answer (From, To, Protocol, RUN) is always on screen and never below a
 * scroll. Everything else is secondary and lives under Advanced.
 *
 * Controls that don't apply to the current protocol are *hidden*, not greyed:
 * a disabled slider still costs a line of visual scanning for no information.
 * Capability limits (DPDK, SACK, DSCP, thread ceiling) still gate what can run.
 *
 * <p>Transfer mode is the second question the panel answers: one large stream, or
 * thousands of discrete frames? Switching to multi-file swaps in the frametest
 * controls and hides the ones that stop meaning anything.
 */
public class ControlPanel extends VBox {

    private record RunLength(String label, long bytes, boolean continuous, int secs) {
        @Override public String toString() { return label; }
    }

    private static final RunLength[] LENGTHS = {
            new RunLength("5 seconds", 0, false, 5),
            new RunLength("10 seconds", 0, false, 10),
            new RunLength("30 seconds", 0, false, 30),
            new RunLength("60 seconds", 0, false, 60),
            new RunLength("Transfer 100 MB", 100L << 20, false, 300),
            new RunLength("Transfer 1 GB", 1024L << 20, false, 600),
            new RunLength("Transfer 10 GB", 10240L << 20, false, 1800),
            new RunLength("Continuous (until stopped)", 0, true, 3600),
    };

    private final Orchestrator orch;
    private final ComboBox<AgentModel> fromBox = new ComboBox<>();
    private final ComboBox<AgentModel> toBox = new ComboBox<>();
    private final ComboBox<Protocol> protoBox = new ComboBox<>();
    private final ComboBox<Architecture> archBox = new ComboBox<>();
    private final ComboBox<RunLength> lengthBox = new ComboBox<>();
    private final ComboBox<TransferMode> modeBox = new ComboBox<>();

    private final Slider threads = new Slider(1, 128, 8);
    private final Slider processes = new Slider(1, 16, 1);
    private final Slider payloadKb = new Slider(1, 256, 32);
    private final Slider targetMbps = new Slider(0, 10000, 0);
    private final ComboBox<Dscp> dscp = new ComboBox<>();
    private final CheckBox dscpEnabled = new CheckBox("DSCP marking");
    private final CheckBox tlsEnabled = new CheckBox("TLS 1.3");
    private final CheckBox singleConn = new CheckBox("One connection, many streams");

    // --- multi-file (frametest) controls ---
    private final ComboBox<FrameMode> frameMode = new ComboBox<>();
    private final ComboBox<FrameSpec.Preset> framePreset = new ComboBox<>();
    private final Spinner<Integer> frameCustomKb = new Spinner<>(1, 1024 * 1024, 12512);
    private final Spinner<Integer> frameCount = new Spinner<>(1, 10_000_000, 1800);
    private final Spinner<Integer> frameFps = new Spinner<>(0, 1000, 0);
    private final Spinner<Integer> frameQueue = new Spinner<>(0, 4096, 0);
    private final Spinner<Integer> framePrebuffer = new Spinner<>(0, 4096, 5);
    private final ComboBox<FrameStorage> frameStorage = new ComboBox<>();
    private final ComboBox<FrameOrder> frameOrder = new ComboBox<>();
    private final TextField framePath = new TextField();
    private final TextField frameDestPath = new TextField();
    private final Label frameSummary = new Label();

    // frametest parity flags that few runs need — folded away under Advanced.
    private final Spinner<Integer> frameHeaderKb = new Spinner<>(0, 1024, 64);
    private final Spinner<Integer> frameFilesPerDir = new Spinner<>(0, 100_000, 0);
    private final Spinner<Integer> frameIos = new Spinner<>(1, 1024, 1);
    private final Spinner<Integer> frameLoops = new Spinner<>(0, 10_000, 0);
    private final Spinner<Integer> frameCrop = new Spinner<>(0, 100, 0);
    private final Spinner<Integer> frameAsync = new Spinner<>(0, 1024, 0);
    private final Spinner<Integer> framePause = new Spinner<>(0, 3600, 0);
    private final TextField frameName = new TextField();
    private final CheckBox frameOutOfOrder = new CheckBox("Out-of-order completion (-o)");
    private final CheckBox frameDirectIo = new CheckBox("Direct I/O (bypass page cache)");

    // --- fan-out ---
    private final CheckBox fanoutEnabled = new CheckBox("Fan out across several agents");
    private final ListView<AgentModel> fanoutSenders = new ListView<>();
    private final ComboBox<Orchestrator.FanoutShape> fanoutShape = new ComboBox<>();

    private final Deque<Scenario> queue = new ArrayDeque<>();
    private final Button runBtn = new Button("▶   Run test");
    private final Button queueBtn = new Button("+ Queue");
    private final Button runQueueBtn = new Button("Run queue");
    private final Label status = new Label();

    private VBox archRow, procRow, payloadRow, rateRow, dscpRow;
    private VBox frameGroup, frameCustomRow, frameDestPathRow, framePathRow, frameAdvanced;
    private VBox fanoutGroup;
    // Grid labels are hidden alongside their controls; hiding only the control
    // leaves an orphaned label pointing at empty space.
    private Label fromLabel, lengthLabel;

    public ControlPanel(Orchestrator orch, ObservableList<AgentModel> agents) {
        this.orch = orch;
        getStyleClass().add("panel");
        setSpacing(10);

        Label title = new Label("RUN A TEST");
        title.getStyleClass().add("panel-title");

        fromBox.setItems(agents);
        toBox.setItems(agents);
        fromBox.setMaxWidth(Double.MAX_VALUE);
        toBox.setMaxWidth(Double.MAX_VALUE);
        fromBox.setPromptText("no agents connected");
        toBox.setPromptText("no agents connected");

        protoBox.getItems().setAll(Protocol.values());
        protoBox.getSelectionModel().select(Protocol.TCP);
        protoBox.setMaxWidth(Double.MAX_VALUE);
        archBox.getItems().setAll(Architecture.values());
        archBox.getSelectionModel().select(Architecture.SELECTOR);
        archBox.setMaxWidth(Double.MAX_VALUE);
        lengthBox.getItems().setAll(LENGTHS);
        lengthBox.getSelectionModel().select(1);
        lengthBox.setMaxWidth(Double.MAX_VALUE);
        modeBox.getItems().setAll(TransferMode.values());
        modeBox.getSelectionModel().select(TransferMode.LARGE_FILE);
        modeBox.setMaxWidth(Double.MAX_VALUE);

        // --- the essential four, always visible ---
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(7);
        ColumnConstraints c0 = new ColumnConstraints();
        c0.setMinWidth(62);
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(c0, c1);
        // "From"/"To" rather than two role nouns: which way this run pushes
        // data is a property of the run, not of either agent. Any agent can be
        // on either side, of several runs at once.
        fromLabel = field("From");
        grid.addRow(0, fromLabel, fromBox);
        grid.addRow(1, field("To"), toBox);
        grid.addRow(2, field("Protocol"), protoBox);
        grid.addRow(3, field("Sending"), modeBox);
        lengthLabel = field("Length");
        grid.addRow(4, lengthLabel, lengthBox);

        HBox protoFlags = new HBox(12, tlsEnabled, singleConn);
        protoFlags.setAlignment(Pos.CENTER_LEFT);
        protoFlags.setPadding(new Insets(0, 0, 0, 70));

        VBox streamsRow = slider("Concurrent streams", threads, "");

        buildFrameControls();
        buildFanoutControls(agents);

        // --- primary action ---
        runBtn.getStyleClass().add("btn-run");
        runBtn.setMaxWidth(Double.MAX_VALUE);
        runBtn.setMinHeight(44);
        queueBtn.getStyleClass().add("btn-ghost");
        runQueueBtn.getStyleClass().add("btn-ghost");
        queueBtn.setMaxWidth(Double.MAX_VALUE);
        runQueueBtn.setMaxWidth(Double.MAX_VALUE);
        HBox queueRow = new HBox(8, queueBtn, runQueueBtn);
        HBox.setHgrow(queueBtn, Priority.ALWAYS);
        HBox.setHgrow(runQueueBtn, Priority.ALWAYS);
        status.getStyleClass().add("hint");
        status.setWrapText(true);

        // --- everything else, folded away ---
        archRow = comboRow("Architecture", archBox);
        procRow = slider("Concurrent processes", processes, "");
        payloadRow = slider("Payload size", payloadKb, " KB");
        rateRow = slider("Offered rate", targetMbps, " Mbps");
        dscp.getItems().setAll(Dscp.values());
        dscp.getSelectionModel().select(Dscp.CS0);
        dscp.setCellFactory(v -> dscpCell(true));
        dscp.setButtonCell(dscpCell(false));
        dscpRow = comboRow("DSCP code point", dscp);
        VBox advBody = new VBox(10, archRow, procRow, payloadRow, rateRow,
                dscpEnabled, dscpRow, frameAdvanced);
        advBody.setPadding(new Insets(8, 2, 2, 2));
        TitledPane advanced = new TitledPane("Advanced", advBody);
        advanced.setExpanded(false);
        advanced.getStyleClass().add("advanced");

        getChildren().addAll(title, grid, protoFlags, streamsRow, frameGroup,
                fanoutGroup, runBtn, queueRow, status, advanced);

        Runnable recompute = this::refresh;
        fromBox.valueProperty().addListener((o, a, b) -> recompute.run());
        toBox.valueProperty().addListener((o, a, b) -> recompute.run());
        protoBox.valueProperty().addListener((o, a, b) -> recompute.run());
        lengthBox.valueProperty().addListener((o, a, b) -> recompute.run());
        modeBox.valueProperty().addListener((o, a, b) -> recompute.run());
        dscpEnabled.selectedProperty().addListener((o, a, b) -> recompute.run());
        framePreset.valueProperty().addListener((o, a, b) -> recompute.run());
        frameMode.valueProperty().addListener((o, a, b) -> recompute.run());
        frameStorage.valueProperty().addListener((o, a, b) -> recompute.run());
        fanoutEnabled.selectedProperty().addListener((o, a, b) -> recompute.run());
        framePath.textProperty().addListener((o, a, b) -> recompute.run());
        for (Spinner<Integer> sp : List.of(frameCount, frameFps, frameQueue, frameCustomKb,
                frameLoops, frameHeaderKb)) {
            sp.valueProperty().addListener((o, a, b) -> recompute.run());
        }
        fanoutSenders.getSelectionModel().getSelectedItems().addListener(
                (javafx.collections.ListChangeListener<AgentModel>) c -> recompute.run());
        agents.addListener((javafx.collections.ListChangeListener<AgentModel>) c -> {
            autoSelect();
            recompute.run();
        });

        runBtn.setOnAction(e -> runSingle());
        queueBtn.setOnAction(e -> enqueue());
        runQueueBtn.setOnAction(e -> runQueue());

        autoSelect();
        refresh();
    }

    /** The frametest controls: the common ones inline, the long tail under Advanced. */
    private void buildFrameControls() {
        frameMode.getItems().setAll(FrameMode.values());
        frameMode.getSelectionModel().select(FrameMode.WRITE);
        framePreset.getItems().setAll(FrameSpec.Preset.values());
        framePreset.getSelectionModel().select(FrameSpec.Preset.FOUR_K);
        frameStorage.getItems().setAll(FrameStorage.values());
        frameStorage.getSelectionModel().select(FrameStorage.DISK);
        frameOrder.getItems().setAll(FrameOrder.values());
        frameOrder.getSelectionModel().select(FrameOrder.SEQUENTIAL);
        for (Spinner<Integer> sp : List.of(frameCustomKb, frameCount, frameFps, frameQueue,
                framePrebuffer, frameHeaderKb, frameFilesPerDir, frameIos, frameLoops,
                frameCrop, frameAsync, framePause)) {
            sp.setEditable(true);
            sp.setMaxWidth(Double.MAX_VALUE);
        }
        framePath.setPromptText("/mnt/san/frames  (on the sender agent)");
        frameDestPath.setPromptText("same as sender path");
        frameName.setPromptText("frame%06u.tst");
        frameDirectIo.setSelected(true);
        frameSummary.getStyleClass().add("hint");
        frameSummary.setWrapText(true);

        frameCustomRow = spinnerRow("Frame size (KB)", frameCustomKb);
        framePathRow = new VBox(4, sectionLabel("Frame directory"), framePath);
        frameDestPathRow = new VBox(4, sectionLabel("Destination directory"), frameDestPath);

        HBox countFps = new HBox(8,
                spinnerRow("Frames", frameCount),
                spinnerRow("Target fps (0 = unpaced)", frameFps));
        countFps.getChildren().forEach(n -> HBox.setHgrow(n, Priority.ALWAYS));
        HBox queueBuf = new HBox(8,
                spinnerRow("Queue depth (drops)", frameQueue),
                spinnerRow("Pre-buffer", framePrebuffer));
        queueBuf.getChildren().forEach(n -> HBox.setHgrow(n, Priority.ALWAYS));

        frameGroup = new VBox(9,
                comboRow("Frame test", frameMode),
                comboRow("Frame size", framePreset),
                frameCustomRow,
                countFps,
                queueBuf,
                comboRow("Access order", frameOrder),
                comboRow("Frame storage", frameStorage),
                framePathRow,
                frameDestPathRow,
                frameSummary);
        frameGroup.setPadding(new Insets(4, 0, 2, 0));

        frameAdvanced = new VBox(9,
                sectionLabel("FRAMETEST PARITY"),
                spinnerRow("Header size (KB, --header)", frameHeaderKb),
                spinnerRow("Files per directory (-d)", frameFilesPerDir),
                spinnerRow("I/Os per frame (-i)", frameIos),
                spinnerRow("Extra loops (-l)", frameLoops),
                spinnerRow("Crop, middle % (-c)", frameCrop),
                spinnerRow("Async depth (-a)", frameAsync),
                spinnerRow("Pause before start, s (-p)", framePause),
                new VBox(4, sectionLabel("Filename pattern (--name)"), frameName),
                frameOutOfOrder,
                frameDirectIo);
    }

    private void buildFanoutControls(ObservableList<AgentModel> agents) {
        fanoutSenders.setItems(agents);
        fanoutSenders.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        fanoutSenders.setPrefHeight(96);
        fanoutShape.getItems().setAll(Orchestrator.FanoutShape.values());
        fanoutShape.getSelectionModel().select(Orchestrator.FanoutShape.INCAST);
        fanoutShape.setMaxWidth(Double.MAX_VALUE);

        Label hint = new Label("Each selected agent runs the same scenario at the same time.");
        hint.getStyleClass().add("hint");
        hint.setWrapText(true);

        VBox body = new VBox(6, sectionLabel("From agents"), fanoutSenders,
                comboRow("Shape", fanoutShape), hint);
        fanoutGroup = new VBox(6, fanoutEnabled, body);
        // Only the body hides; the checkbox that reveals it must stay visible.
        fanoutEnabled.selectedProperty().addListener((o, a, b) -> show(body, b));
        show(body, false);
    }

    /** Switch transfer mode programmatically — used by the snapshot harness so
     *  the multi-file controls get looked at, not just the large-file ones. */
    public void selectTransferMode(TransferMode m) {
        modeBox.getSelectionModel().select(m);
        refresh();
    }

    /** Reveal the fan-out controls, for the same reason. */
    public void setFanoutVisible(boolean v) {
        fanoutEnabled.setSelected(v);
        refresh();
    }

    private void autoSelect() {
        if (fromBox.getValue() == null && !fromBox.getItems().isEmpty()) {
            fromBox.getSelectionModel().selectFirst();
        }
        if (toBox.getValue() == null) {
            for (AgentModel a : toBox.getItems()) {
                if (a != fromBox.getValue()) { toBox.getSelectionModel().select(a); break; }
            }
        }
    }

    /** Show only what applies, and say plainly why Run is unavailable. */
    private void refresh() {
        AgentModel src = fromBox.getValue(), receiver = toBox.getValue();
        Protocol p = protoBox.getValue();
        boolean quic = p != null && p.isQuic();
        boolean udpFamily = p == Protocol.UDP || p == Protocol.UDP_DPDK;
        boolean frames = modeBox.getValue() == TransferMode.MULTI_FILE;
        boolean disk = frameStorage.getValue() == FrameStorage.DISK;

        show(frameGroup, frames);
        show(frameAdvanced, frames);
        show(frameCustomRow, frames && framePreset.getValue() == FrameSpec.Preset.CUSTOM);
        // Empty-frame mode moves no payload, so a size control would be a lie.
        show(framePathRow, frames && disk);
        show(frameDestPathRow, frames && disk);

        // Multi-file always runs on OS threads — per-frame file I/O is blocking,
        // so there is nothing for a reactor to multiplex and the Threaded/Selector
        // comparison would be meaningless. Same reason QUIC hides it.
        show(archRow, !quic && !frames);
        show(singleConn, quic);
        show(tlsEnabled, p != null && p.supportsTlsToggle());
        show(rateRow, udpFamily && !frames);
        show(payloadRow, !frames);          // frame size replaces payload size
        show(dscpRow, dscpEnabled.isSelected());
        // A frame run is bounded by its frame count, not a clock or byte target.
        show(lengthBox, !frames);
        show(lengthLabel, !frames);
        if (quic) tlsEnabled.setSelected(true);

        // Fanning out replaces the single-sender picker with the multi-select
        // list further down, so the whole grid row goes rather than just the box.
        boolean fanout = fanoutEnabled.isSelected();
        show(fromBox, !fanout);
        show(fromLabel, !fanout);

        boolean canDscp = both(src, receiver, a -> a.caps.dscp);
        dscpEnabled.setDisable(!canDscp);
        if (!canDscp) dscpEnabled.setSelected(false);

        // The stream count is a fixed 1..128 for every protocol and mode. It used
        // to be re-derived from the agents' reported maxThreads, which silently
        // pinned the slider to 1..2 whenever an agent registered without that
        // field. Every engine clamps to what it can actually run anyway.
        if (frames) frameSummary.setText(describeFrameRun());

        String blocker = blocker(src, receiver, p);
        runBtn.setDisable(blocker != null);
        queueBtn.setDisable(blocker != null);
        status.setText(blocker != null ? blocker : queueSummary());
    }

    /** Plain-language preview of what the frame run will actually move. */
    private String describeFrameRun() {
        FrameSpec f = buildFrameSpec();
        StringBuilder sb = new StringBuilder();
        sb.append(f.totalFrames()).append(" frames × ")
          .append(Scenario.humanBytes(f.frameBytes))
          .append("  =  ").append(Scenario.humanBytes(f.totalBytes()));
        if (f.fpsLimit > 0) {
            double secs = f.totalFrames() / f.fpsLimit;
            sb.append("   ·   ").append(String.format("%.0f s at %.0f fps", secs, f.fpsLimit));
            // The rate the link must sustain for this to play without dropping.
            double mbps = f.fpsLimit * f.payloadBytes() * 8 / 1.0e6;
            sb.append(String.format("   ·   needs %.0f Mbps", mbps));
        }
        FrameSpec.Preset p = framePreset.getValue();
        if (p != null && !p.verified && p != FrameSpec.Preset.CUSTOM) {
            sb.append("\nNote: the ").append(p.key)
              .append(" preset size is inferred, not confirmed against DVS frametest.");
        }
        return sb.toString();
    }

    /** Human explanation of why a run can't start, or null when it can. */
    private String blocker(AgentModel src, AgentModel receiver, Protocol p) {
        boolean frames = modeBox.getValue() == TransferMode.MULTI_FILE;
        List<AgentModel> senders = selectedSenders();

        if (senders.isEmpty() || receiver == null) {
            return fanoutEnabled.isSelected() && receiver != null
                    ? "Select one or more agents to fan out from."
                    : "Start two agents pointing at this console, then pick From and To.";
        }
        for (AgentModel s : senders) {
            if (s == receiver && fanoutShape.getValue() != Orchestrator.FanoutShape.PAIRS) {
                return "From and To must be different agents.";
            }
            if (!s.onlineProperty().get()) return s.getName() + " is offline.";
        }
        if (!receiver.onlineProperty().get()) return "The selected receiver is offline.";

        for (AgentModel s : senders) {
            if (p != null && p.requiresDpdk && !both(s, receiver, a -> a.caps.dpdk)) {
                return p.label + " needs DPDK on both agents — "
                        + missing(s, receiver, a -> a.caps.dpdk) + " can't do it.";
            }
            if (p != null && p.requiresSack && !both(s, receiver, a -> a.caps.sack)) {
                return p.label + " needs SACK on both agents — "
                        + missing(s, receiver, a -> a.caps.sack) + " can't do it.";
            }
        }

        if (frames) {
            // A frame either arrives whole or it did not arrive; UDP guarantees
            // neither, so "frames transferred" would not mean anything.
            if (p == Protocol.UDP || p == Protocol.UDP_DPDK) {
                return "Multi-file needs a reliable transport — a torn frame isn't a frame. "
                        + "Use TCP, TCP + SACK or QUIC.";
            }
            if (frameStorage.getValue() == FrameStorage.DISK && framePath.getText().isBlank()) {
                return "Give a frame directory, or switch storage to Memory.";
            }
            if (frameCount.getValue() == null || frameCount.getValue() < 1) {
                return "Frame count must be at least 1.";
            }
            // A pre-buffer deeper than the queue can never be staged: the queue
            // fills, the rest of the pre-buffer is refused, and the pacer is left
            // trying to hit deadlines for frames it never queued. 0 = unbounded.
            if (frameQueue.getValue() > 0 && framePrebuffer.getValue() > frameQueue.getValue()) {
                return "Pre-buffer (" + framePrebuffer.getValue() + ") can't exceed queue depth ("
                        + frameQueue.getValue() + ") — raise the queue, or set it to 0 for unbounded.";
            }
            if (framePreset.getValue() == FrameSpec.Preset.CUSTOM
                    && frameCustomKb.getValue() * 1024L <= frameHeaderKb.getValue() * 1024L) {
                return "Frame size must exceed the header size.";
            }
        }
        return null;
    }

    private String queueSummary() {
        return queue.isEmpty() ? "Ready." : queue.size() + " scenario(s) queued.";
    }

    private interface CapCheck { boolean ok(AgentModel a); }
    private boolean both(AgentModel a, AgentModel b, CapCheck f) {
        return a != null && b != null && f.ok(a) && f.ok(b);
    }
    private String missing(AgentModel a, AgentModel b, CapCheck f) {
        if (!f.ok(a)) return a.getName();
        return b.getName();
    }

    private static void show(javafx.scene.Node n, boolean visible) {
        n.setVisible(visible);
        n.setManaged(visible);
    }

    /** Senders for this run: the multi-select list when fanning out, else the box. */
    private List<AgentModel> selectedSenders() {
        if (fanoutEnabled.isSelected()) {
            return new ArrayList<>(fanoutSenders.getSelectionModel().getSelectedItems());
        }
        AgentModel s = fromBox.getValue();
        return s == null ? List.of() : List.of(s);
    }

    private FrameSpec buildFrameSpec() {
        FrameSpec f = new FrameSpec();
        f.mode = frameMode.getValue().wire;
        FrameSpec.Preset p = framePreset.getValue();
        f.headerKb = frameHeaderKb.getValue();
        f.frameBytes = p == FrameSpec.Preset.CUSTOM
                ? frameCustomKb.getValue() * 1024L
                : p.payloadBytes() + f.headerKb * 1024L;
        f.frameCount = frameCount.getValue();
        f.fpsLimit = frameFps.getValue();
        f.queueDepth = frameQueue.getValue();
        f.prebuffer = framePrebuffer.getValue();
        f.order = frameOrder.getValue().wire;
        f.storage = frameStorage.getValue().wire;
        f.path = framePath.getText().trim();
        f.destPath = frameDestPath.getText().trim();
        f.filesPerDir = frameFilesPerDir.getValue();
        f.namePattern = frameName.getText().trim();
        f.asyncDepth = frameAsync.getValue();
        f.outOfOrder = frameOutOfOrder.isSelected();
        f.loopFrames = frameLoops.getValue();
        f.iosPerFrame = frameIos.getValue();
        f.cropPercent = frameCrop.getValue();
        f.pauseSecs = framePause.getValue();
        f.directIo = frameDirectIo.isSelected();
        return f;
    }

    private Scenario currentScenario() {
        RunLength rl = lengthBox.getValue();
        Protocol p = protoBox.getValue();
        boolean udpFamily = p == Protocol.UDP || p == Protocol.UDP_DPDK;
        boolean frames = modeBox.getValue() == TransferMode.MULTI_FILE;
        Scenario sc = Scenario.of(p, archBox.getValue(),
                (int) threads.getValue(), (int) processes.getValue(),
                dscp.getValue().value, dscpEnabled.isSelected(),
                (int) payloadKb.getValue() * 1024,
                udpFamily && !frames ? (int) targetMbps.getValue() : 0,
                rl.secs())
                .withTls(tlsEnabled.isSelected())
                .withSingleConnection(singleConn.isSelected() && p != null && p.isQuic());
        if (frames) {
            // A frame run's bound is its frame count; a byte target or a
            // continuous flag would fight with it.
            return sc.withFrames(buildFrameSpec());
        }
        return sc.withBytesTarget(rl.bytes()).withContinuous(rl.continuous());
    }

    private void runSingle() {
        List<AgentModel> senders = selectedSenders();
        AgentModel receiver = toBox.getValue();
        if (senders.isEmpty() || receiver == null) return;
        Scenario sc = currentScenario();
        if (fanoutEnabled.isSelected() && senders.size() > 1) {
            orch.startFanout(senders, List.of(receiver), sc, fanoutShape.getValue());
        } else {
            orch.startRun(senders.get(0), receiver, sc);
        }
    }

    private void enqueue() {
        queue.addLast(currentScenario());
        runQueueBtn.setText("Run queue (" + queue.size() + ")");
        status.setText(queueSummary());
    }

    private void runQueue() {
        List<AgentModel> senders = selectedSenders();
        if (senders.isEmpty() || toBox.getValue() == null) return;
        if (queue.isEmpty()) { runSingle(); return; }
        Deque<Scenario> copy = new ArrayDeque<>(queue);
        queue.clear();
        runQueueBtn.setText("Run queue");
        status.setText("Running " + copy.size() + " queued scenario(s)…");
        orch.startBatch(senders.get(0), toBox.getValue(), copy, null);
    }

    // --- builders ---

    private Label field(String t) {
        Label l = new Label(t);
        l.getStyleClass().add("field-label");
        return l;
    }

    private Label sectionLabel(String t) {
        Label l = new Label(t);
        l.getStyleClass().add("section-label");
        return l;
    }

    /**
     * Two-line cells in the popup — name and code point on top, per-hop
     * behaviour and RFC beneath — but a single compact line in the closed box,
     * where the row is only as tall as the other controls.
     */
    private ListCell<Dscp> dscpCell(boolean expanded) {
        return new ListCell<>() {
            @Override protected void updateItem(Dscp d, boolean empty) {
                super.updateItem(d, empty);
                if (empty || d == null) { setText(null); setGraphic(null); return; }
                if (!expanded) {
                    setGraphic(null);
                    setText(d.name + "  (" + d.value + ")");
                    return;
                }
                Label head = new Label(d.name + "  (" + d.value + ")");
                head.getStyleClass().add("field-label");
                Label sub = new Label(d.use + "  ·  " + d.rfc);
                sub.getStyleClass().add("hint");
                setText(null);
                setGraphic(new VBox(1, head, sub));
            }
        };
    }

    private VBox comboRow(String name, ComboBox<?> box) {
        box.setMaxWidth(Double.MAX_VALUE);
        return new VBox(4, sectionLabel(name), box);
    }

    private VBox spinnerRow(String name, Spinner<Integer> sp) {
        sp.setMaxWidth(Double.MAX_VALUE);
        return new VBox(4, sectionLabel(name), sp);
    }

    private VBox slider(String name, Slider s, String unit) {
        Label lbl = new Label(name);
        lbl.getStyleClass().add("section-label");
        Label val = new Label();
        val.getStyleClass().add("value-pill");
        s.setSnapToTicks(true);
        s.setMajorTickUnit(1);
        s.setMinorTickCount(0);
        s.setBlockIncrement(1);
        Runnable upd = () -> val.setText((int) s.getValue() + unit);
        s.valueProperty().addListener((o, a, b) -> {
            s.setValue(Math.round(b.doubleValue()));
            upd.run();
        });
        upd.run();
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox head = new HBox(8, lbl, spacer, val);
        head.setAlignment(Pos.CENTER_LEFT);
        return new VBox(3, head, s);
    }
}

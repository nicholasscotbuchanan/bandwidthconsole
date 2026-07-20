package com.bwtest.console.ui;

import com.bwtest.console.Orchestrator;
import com.bwtest.console.model.RunRecord;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ObjectProperty;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.*;

/**
 * The console's whole layout, in one place so the live app and the screenshot
 * harness ({@link UiSnapshot}) render exactly the same thing.
 */
public class ConsoleUI {

    private final BorderPane root = new BorderPane();
    private final TabPane tabs = new TabPane();
    private ControlPanel controls;

    public ConsoleUI(Orchestrator orch, int port, ObjectProperty<RunRecord> selected) {
        AgentsPane agentsPane = new AgentsPane(orch.agents);
        this.controls = new ControlPanel(orch, orch.agents);
        ControlPanel controls = this.controls;
        PastRunsPane pastRuns = new PastRunsPane(orch);
        VBox left = new VBox(12, agentsPane, controls, pastRuns);
        left.setPadding(new Insets(12));
        VBox.setVgrow(agentsPane, Priority.SOMETIMES);
        ScrollPane leftScroll = new ScrollPane(left);
        leftScroll.setFitToWidth(true);
        leftScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        leftScroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        leftScroll.setPrefWidth(392);
        leftScroll.setMinWidth(392);

        Surface3D field = new Surface3D(orch.runs, selected);
        // The live 3D pair: per-thread contribution on the left, sender-vs-receiver
        // goodput/throughput balance on the right.
        ThreadContribution3D threads3d = new ThreadContribution3D(orch.runs, selected);
        Balance3D balance3d = new Balance3D(orch.runs, selected);
        HBox live3d = new HBox(10, threads3d, balance3d);
        HBox.setHgrow(threads3d, Priority.ALWAYS);
        HBox.setHgrow(balance3d, Priority.ALWAYS);
        LiveChartView live = new LiveChartView(orch.runs);
        PerStreamChart perStream = new PerStreamChart(selected);
        CpuChart cpu = new CpuChart(orch.runs);
        GanttView gantt = new GanttView(selected);
        FrameIoView frameIo = new FrameIoView(selected);

        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(
                tab("Live 3D", live3d),
                tab("3D Field", field),
                tab("Live Throughput", live),
                tab("Per-stream", perStream),
                tab("CPU", cpu),
                tab("Latency Gantt", gantt),
                tab("Frame I/O", frameIo));

        CompareView compare = new CompareView(orch, orch.runs, selected);

        SplitPane center = new SplitPane(wrap(tabs), compare);
        center.setOrientation(Orientation.VERTICAL);
        center.setDividerPositions(0.63);
        center.setPadding(new Insets(0, 12, 12, 0));

        root.getStyleClass().add("app-bg");
        root.setTop(titleBar(orch, port));
        root.setLeft(leftScroll);
        root.setCenter(center);
        // Pinned to the bottom rather than living in a tab: a run's long silent
        // phases (staging especially) must be visible whichever view is open.
        root.setBottom(new RunStatusBar(orch.runs));
    }

    public BorderPane root() { return root; }
    public TabPane tabs() { return tabs; }
    public ControlPanel controls() { return controls; }

    private HBox titleBar(Orchestrator orch, int port) {
        Label app = new Label("BANDWIDTH");
        app.getStyleClass().add("title-app");
        Label accent = new Label("CONSOLE");
        accent.getStyleClass().addAll("title-app", "title-accent");
        HBox brand = new HBox(8, app, accent);
        brand.setAlignment(Pos.CENTER_LEFT);

        Label sub = new Label("control plane · report nexus");
        sub.getStyleClass().add("title-sub");
        VBox brandBox = new VBox(0, brand, sub);

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);

        Label listening = new Label("listening  tcp/" + port);
        listening.getStyleClass().addAll("title-sub", "mono");
        Label count = new Label();
        count.getStyleClass().add("value-pill");
        count.textProperty().bind(Bindings.size(orch.agents).asString("%d agent(s)"));
        Label runs = new Label();
        runs.getStyleClass().add("value-pill");
        runs.textProperty().bind(Bindings.size(orch.runs).asString("%d run(s)"));

        HBox stats = new HBox(10, listening, count, runs);
        stats.setAlignment(Pos.CENTER_RIGHT);

        HBox bar = new HBox(16, brandBox, sp, stats);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("title-bar");
        return bar;
    }

    private Tab tab(String name, Node content) {
        Tab t = new Tab(name);
        StackPane pane = new StackPane(content);
        pane.setPadding(new Insets(10, 12, 6, 12));
        t.setContent(pane);
        return t;
    }

    private Region wrap(Node n) {
        StackPane p = new StackPane(n);
        p.setPadding(new Insets(12, 12, 6, 12));
        return p;
    }
}

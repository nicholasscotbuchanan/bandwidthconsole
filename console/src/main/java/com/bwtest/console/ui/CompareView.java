package com.bwtest.console.ui;

import com.bwtest.console.Orchestrator;
import com.bwtest.console.model.RunRecord;
import com.bwtest.console.model.Scenario;
import com.bwtest.console.model.Telemetry;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.ListChangeListener;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.util.List;

/** Side-by-side comparison of every run, and the place you pick which run the
 *  Gantt/3D highlight. The winning throughput row is flagged so the optimum
 *  configuration is unmissable. */
public class CompareView extends VBox {

    private final TableView<RunRecord> table = new TableView<>();
    private final Orchestrator orch;

    public CompareView(Orchestrator orch, javafx.collections.ObservableList<RunRecord> runs,
                       ObjectProperty<RunRecord> selected) {
        this.orch = orch;
        getStyleClass().add("panel");
        setSpacing(8);

        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);
        Label title = new Label("SCENARIO COMPARISON");
        title.getStyleClass().add("panel-title");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        Button abort = new Button("Abort selected");
        abort.getStyleClass().add("btn-ghost");
        abort.setOnAction(e -> {
            RunRecord r = table.getSelectionModel().getSelectedItem();
            if (r != null) orch.abort(r);
        });
        header.getChildren().addAll(title, sp, abort);

        buildColumns();
        table.setItems(runs);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        VBox.setVgrow(table, Priority.ALWAYS);
        table.setPlaceholder(new Label("No runs yet — configure a scenario and hit Run."));

        // Selection drives the shared selected-run property (for Gantt/3D).
        table.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> selected.set(b));
        selected.addListener((o, a, b) -> {
            if (b != null && table.getSelectionModel().getSelectedItem() != b) {
                table.getSelectionModel().select(b);
            }
        });

        // Keep the table live as summaries/states arrive; auto-select newest.
        runs.addListener((ListChangeListener<RunRecord>) c -> {
            while (c.next()) {
                if (c.wasAdded()) {
                    for (RunRecord r : c.getAddedSubList()) {
                        r.stateProperty().addListener((x, y, z) -> table.refresh());
                        r.summaryProperty().addListener((x, y, z) -> table.refresh());
                        table.getSelectionModel().select(r);
                    }
                }
            }
        });

        getChildren().addAll(header, table);
    }

    private void buildColumns() {
        table.getColumns().add(col("#", 48, r -> {
            Label l = new Label(String.valueOf(r.index));
            // Explicit fill: without it the number vanishes against the selected
            // row's highlight.
            l.setTextFill(Color.web("#e2e8f0"));
            Circle dot = new Circle(5, r.color);
            HBox h = new HBox(6, dot, l);
            h.setAlignment(Pos.CENTER_LEFT);
            return h;
        }));
        table.getColumns().add(text("Configuration", 210, r -> r.scenario.shortLabel()));
        table.getColumns().add(text("Peak Mbps", 92, r -> num(r.peakMbps())));
        table.getColumns().add(text("Avg Mbps", 92, r ->
                r.getSummary() == null ? "…" : num(r.getSummary().avgMbps())));
        table.getColumns().add(text("p95 RTT", 80, r ->
                r.getSummary() == null ? "…" : num(r.getSummary().p95RttMs()) + " ms"));
        table.getColumns().add(text("Retx", 64, r ->
                r.getSummary() == null ? "…" : String.valueOf(r.getSummary().retransmits())));
        // Frame columns: blank on large-file runs rather than "0", so a
        // side-by-side of the two modes reads as "not applicable", not "none".
        table.getColumns().add(text("fps", 64, r -> {
            if (!r.scenario.isMultiFile()) return "—";
            Telemetry.Summary s = r.getSummary();
            if (s == null || !s.hasFrames() || s.frame().windows().isEmpty()) return "…";
            List<Telemetry.Window> ws = s.frame().windows();
            return String.format("%.1f", ws.get(ws.size() - 1).fps());
        }));
        table.getColumns().add(text("Frames", 78, r ->
                r.scenario.isMultiFile() ? String.format("%,d", r.framesTransferred()) : "—"));
        table.getColumns().add(text("Dropped", 74, r ->
                r.scenario.isMultiFile() ? String.format("%,d", r.framesDropped()) : "—"));
        table.getColumns().add(text("Worst frame", 96, r -> {
            if (!r.scenario.isMultiFile()) return "—";
            Telemetry.Summary s = r.getSummary();
            return s == null || !s.hasFrames() ? "…"
                    : String.format("%.1f ms", s.frame().slowestMs());
        }));
        table.getColumns().add(text("State", 92, r -> r.getState().toString().toLowerCase()));
    }

    private TableColumn<RunRecord, String> text(String name, double w,
                                                java.util.function.Function<RunRecord, String> f) {
        TableColumn<RunRecord, String> c = new TableColumn<>(name);
        c.setPrefWidth(w);
        c.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(f.apply(cd.getValue())));
        c.setCellFactory(tc -> new TableCell<>() {
            @Override protected void updateItem(String v, boolean empty) {
                super.updateItem(v, empty);
                setText(empty ? null : v);
                RunRecord r = empty || getIndex() >= getTableView().getItems().size()
                        ? null : getTableView().getItems().get(getIndex());
                // Flag the best-throughput run on the peak column.
                if (r != null && name.equals("Peak Mbps") && isBest(r)) {
                    setTextFill(Color.web("#5eead4"));
                    setStyle("-fx-font-weight: bold;");
                } else if (r != null && name.equals("Dropped") && r.framesDropped() > 0) {
                    // A dropped frame is a playback failure, not a statistic.
                    setTextFill(Color.web("#f87171"));
                    setStyle("-fx-font-weight: bold;");
                } else if (r != null && r.getState() == RunRecord.State.ERROR) {
                    setTextFill(Color.web("#f87171"));
                    setStyle("");
                } else {
                    setTextFill(Color.web("#dbe4ee"));
                    setStyle("");
                }
            }
        });
        return c;
    }

    private TableColumn<RunRecord, RunRecord> col(String name, double w,
                                                  java.util.function.Function<RunRecord, Region> f) {
        TableColumn<RunRecord, RunRecord> c = new TableColumn<>(name);
        c.setPrefWidth(w);
        c.setSortable(false);
        c.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue()));
        c.setCellFactory(tc -> new TableCell<>() {
            @Override protected void updateItem(RunRecord r, boolean empty) {
                super.updateItem(r, empty);
                setGraphic(empty || r == null ? null : f.apply(r));
            }
        });
        return c;
    }

    private boolean isBest(RunRecord r) {
        double best = 0;
        for (RunRecord o : table.getItems()) {
            if (o.getSummary() != null) best = Math.max(best, o.getSummary().peakMbps());
        }
        return r.getSummary() != null && best > 0 && r.getSummary().peakMbps() >= best;
    }

    private static String num(double v) {
        if (v >= 1000) return String.format("%.1fk", v / 1000);
        return String.format("%.1f", v);
    }
}

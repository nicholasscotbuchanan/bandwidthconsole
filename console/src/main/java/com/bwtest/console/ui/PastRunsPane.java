package com.bwtest.console.ui;

import com.bwtest.console.Orchestrator;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * Past Runs: pull previously stored runs back out of InfluxDB and drop them into
 * the live views for comparison against what's running now.
 */
public class PastRunsPane extends VBox {

    private final Orchestrator orch;
    private final ComboBox<Orchestrator.PastRun> picker = new ComboBox<>();
    private final Label status = new Label();

    public PastRunsPane(Orchestrator orch) {
        this.orch = orch;
        getStyleClass().add("panel");
        setSpacing(8);

        Label title = new Label("PAST RUNS");
        title.getStyleClass().add("panel-title");

        picker.setMaxWidth(Double.MAX_VALUE);
        picker.setPromptText("— select a stored run —");

        Button refresh = new Button("↻");
        refresh.getStyleClass().add("btn-ghost");
        Button load = new Button("Load replay");
        load.getStyleClass().add("btn-ghost");
        load.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(load, Priority.ALWAYS);

        HBox row = new HBox(8, load, refresh);
        row.setAlignment(Pos.CENTER_LEFT);

        status.getStyleClass().add("hint");
        status.setText(orch.influx.isEnabled()
                ? "influx: " + orch.influx.describe()
                : "influx: disabled — set BW_INFLUX_URL to persist and replay runs");
        status.setWrapText(true);

        refresh.setOnAction(e -> reload());
        load.setOnAction(e -> {
            Orchestrator.PastRun pr = picker.getValue();
            if (pr == null) return;
            status.setText("loading " + pr.label() + "…");
            new Thread(() -> {
                orch.loadReplay(pr);
                Platform.runLater(() -> status.setText("replayed " + pr.label()));
            }, "replay-load").start();
        });

        getChildren().addAll(title, picker, row, status);

        if (orch.influx.isEnabled()) {
            reload();
        } else {
            picker.setDisable(true);
            load.setDisable(true);
            refresh.setDisable(true);
        }
    }

    private void reload() {
        new Thread(() -> {
            List<Orchestrator.PastRun> runs = orch.listPastRuns();
            Platform.runLater(() -> {
                picker.getItems().setAll(runs);
                status.setText(runs.isEmpty()
                        ? "no stored runs found"
                        : runs.size() + " stored run(s)");
            });
        }, "past-runs").start();
    }
}

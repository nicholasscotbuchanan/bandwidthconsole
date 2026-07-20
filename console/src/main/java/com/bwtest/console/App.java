package com.bwtest.console;

import com.bwtest.console.model.RunRecord;
import com.bwtest.console.net.ControlServer;
import com.bwtest.console.ui.*;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/**
 * Bandwidth Console — the control plane and report nexus. Listens for bwagent
 * connections, drives scenarios, and renders the 3D field, live throughput,
 * latency Gantt, and scenario comparison.
 *
 * Startup shows a small loader stage first: the main scene builds three 3D
 * sub-scenes and a lot of controls, which takes long enough on a cold JVM
 * that launching looked like nothing was happening.
 */
public class App extends Application {

    private ControlServer server;

    @Override
    public void start(Stage stage) {
        int port = Integer.parseInt(System.getenv().getOrDefault("BW_CONSOLE_PORT",
                getParameters().getRaw().isEmpty() ? "9077" : getParameters().getRaw().get(0)));

        // Loader first — it paints within a frame or two of JVM start.
        Stage splash = new Stage(StageStyle.UNDECORATED);
        Label status = new Label("starting control plane…");
        splash.setScene(splashScene(status));
        splash.centerOnScreen();
        splash.show();

        // Let the splash render one frame before doing the heavy work, then
        // build in small steps so the status line actually updates.
        Platform.runLater(() -> {
            status.setText("binding control port tcp/" + port + "…");
            Platform.runLater(() -> {
                Orchestrator orch = new Orchestrator();
                server = new ControlServer(port, orch);
                try {
                    server.start();
                } catch (Exception e) {
                    System.err.println("Failed to bind control port " + port + ": " + e.getMessage());
                }
                status.setText("building views…");
                // Dev knob: BW_SPLASH_HOLD_MS keeps the loader up long enough
                // to look at it; 0/unset in normal use.
                long hold = Long.parseLong(
                        System.getenv().getOrDefault("BW_SPLASH_HOLD_MS", "0"));
                javafx.animation.PauseTransition pause =
                        new javafx.animation.PauseTransition(javafx.util.Duration.millis(Math.max(1, hold)));
                pause.setOnFinished(ev -> {
                    ObjectProperty<RunRecord> selected = new SimpleObjectProperty<>();
                    ConsoleUI ui = new ConsoleUI(orch, port, selected);
                    Scene scene = new Scene(ui.root(), 1500, 940);
                    scene.getStylesheets().add(getClass().getResource("app.css").toExternalForm());
                    stage.setTitle("Bandwidth Console");
                    stage.setScene(scene);
                    stage.show();
                    splash.close();
                });
                pause.play();
            });
        });
    }

    /** Small branded loader: wordmark, spinner, live status line.
     *  Static so the snapshot harness can render it too. */
    public static Scene splashScene(Label status) {
        Label app = new Label("BANDWIDTH");
        app.getStyleClass().add("title-app");
        Label accent = new Label("CONSOLE");
        accent.getStyleClass().addAll("title-app", "title-accent");
        HBox brand = new HBox(8, app, accent);
        brand.setAlignment(Pos.CENTER);

        ProgressIndicator spin = new ProgressIndicator();
        spin.setPrefSize(34, 34);

        status.getStyleClass().add("hint");

        VBox box = new VBox(14, brand, spin, status);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(28, 44, 26, 44));
        box.getStyleClass().add("splash");

        Scene s = new Scene(box, 380, 170);
        s.setFill(Color.web("#0a101c"));
        s.getStylesheets().add(App.class.getResource("app.css").toExternalForm());
        return s;
    }

    @Override
    public void stop() {
        if (server != null) server.stop();
    }

    public static void main(String[] args) {
        launch(args);
    }
}

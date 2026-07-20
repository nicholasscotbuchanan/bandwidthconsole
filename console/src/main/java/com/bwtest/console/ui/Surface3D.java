package com.bwtest.console.ui;

import com.bwtest.console.model.Protocol;
import com.bwtest.console.model.RunRecord;
import com.bwtest.console.model.Scenario;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.*;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.transform.Rotate;

import java.util.*;

/**
 * The signature view: a 3D field of every completed run.
 *
 *   X — concurrent streams (the axis you sweep)
 *   Z — configuration (protocol + architecture), one labelled row each
 *   Y — throughput
 *
 * Z is the *configuration*, not process count, for a concrete reason: several
 * runs usually share a thread/process coordinate (e.g. TCP-Selector and
 * TCP-Threaded both at 8 streams), and mapping those to the same cell stacked
 * bars on top of each other and silently hid results. One row per configuration
 * gives every run its own cell, and makes "which config wins, and at how many
 * streams" readable at a glance — the actual question the tool exists to answer.
 *
 * Drag to orbit, scroll to zoom.
 */
public class Surface3D extends StackPane {

    private static final double SPAN = 260;    // half-extent of the floor
    private static final double MAX_BAR = 260; // tallest bar, world units

    private final ObservableList<RunRecord> runs;
    private final ObjectProperty<RunRecord> selected;

    private final Group content = new Group();
    /** Full manual camera, isometric home view matching the Live 3D panes. */
    private final CameraRig rig = new CameraRig(-45, 35.264, -45, 4000, -120);
    private final Label caption = new Label();
    private SubScene sub;

    public Surface3D(ObservableList<RunRecord> runs, ObjectProperty<RunRecord> selected) {
        this.runs = runs;
        this.selected = selected;
        getStyleClass().add("panel");
        setMinSize(400, 320);

        buildScene();
        rebuild();

        runs.addListener((ListChangeListener<RunRecord>) c -> hookAndRebuild());
        selected.addListener((o, a, b) -> rebuild());
        hookAll();
    }

    private void buildScene() {
        Group root3d = new Group(content);
        root3d.getChildren().addAll(
                new AmbientLight(Color.web("#4a5570")),
                keyLight(-600, -900, -700, "#e8f0ff"),
                keyLight(700, -400, 500, "#3a5f7a"));

        sub = new SubScene(root3d, 400, 320, true, SceneAntialiasing.BALANCED);
        sub.setFill(Color.web("#060a11"));
        sub.widthProperty().bind(widthProperty());
        sub.heightProperty().bind(heightProperty());
        rig.attach(sub, content);

        Label t = new Label("THROUGHPUT FIELD");
        t.getStyleClass().add("panel-title");
        Label ax = new Label("X → concurrent streams      Z → configuration      Y → peak throughput");
        ax.getStyleClass().add("hint");
        caption.getStyleClass().add("hint");
        VBox overlay = new VBox(2, t, ax, caption);
        overlay.setPickOnBounds(false);
        overlay.setMouseTransparent(true);
        StackPane.setAlignment(overlay, Pos.TOP_LEFT);
        StackPane.setMargin(overlay, new Insets(12));

        Label hint = new Label("drag orbit · shift-drag pan · scroll zoom");
        hint.getStyleClass().add("hint");
        StackPane.setAlignment(hint, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(hint, new Insets(10));

        var cam = rig.controls();
        StackPane.setAlignment(cam, Pos.BOTTOM_LEFT);
        StackPane.setMargin(cam, new Insets(10));

        getChildren().addAll(sub, overlay, hint, cam);
    }

    private PointLight keyLight(double x, double y, double z, String col) {
        PointLight l = new PointLight(Color.web(col));
        l.setTranslateX(x);
        l.setTranslateY(y);
        l.setTranslateZ(z);
        return l;
    }

    private final Set<String> hooked = new HashSet<>();
    private void hookAll() { for (RunRecord r : runs) hookOne(r); }
    private void hookAndRebuild() { hookAll(); rebuild(); }
    private void hookOne(RunRecord r) {
        if (hooked.add(r.id)) {
            r.summaryProperty().addListener((o, a, b) -> Platform.runLater(this::rebuild));
        }
    }

    /** Configuration key for the Z axis: what actually distinguishes a run. */
    private static String configKey(Scenario sc) {
        Protocol p = Scenario.fromWire(sc.protocol);
        String name = p == null ? sc.protocol : p.label;
        if (p != null && p.supportsTlsToggle() && sc.tls) name += "+TLS";
        if (p == null || !p.isQuic()) {
            name += "Selector".equals(sc.architecture) ? " · Selector" : " · Threaded";
        }
        return name;
    }

    private void rebuild() {
        content.getChildren().clear();

        List<RunRecord> done = new ArrayList<>();
        for (RunRecord r : runs) {
            if (r.getSummary() != null) done.add(r);
        }
        if (done.isEmpty()) {
            caption.setText("No completed runs yet — run a test and it appears here.");
            content.getChildren().add(buildFloor(List.of(), List.of()));
            return;
        }

        // Axis domains: distinct stream counts (X) and configurations (Z).
        TreeSet<Integer> threadSet = new TreeSet<>();
        LinkedHashSet<String> configSet = new LinkedHashSet<>();
        double maxTput = 1;
        for (RunRecord r : done) {
            threadSet.add(r.scenario.threads);
            configSet.add(configKey(r.scenario));
            maxTput = Math.max(maxTput, r.getSummary().peakMbps());
        }
        List<Integer> xs = new ArrayList<>(threadSet);
        List<String> zs = new ArrayList<>(configSet);
        caption.setText(done.size() + " runs · " + zs.size() + " configs · peak "
                + fmt(maxTput) + " Mbit/s");

        content.getChildren().add(buildFloor(xs, zs));

        RunRecord best = null;
        for (RunRecord r : done) {
            if (best == null || r.getSummary().peakMbps() > best.getSummary().peakMbps()) best = r;
        }
        for (RunRecord r : done) {
            addBar(r, xs, zs, maxTput, r == best);
        }
    }

    /** Floor grid plus axis tick labels laid flat so they read like a chart. */
    private Group buildFloor(List<Integer> xs, List<String> zs) {
        Group floor = new Group();
        Color grid = Color.web("#16243c");
        int div = 8;
        for (int i = 0; i <= div; i++) {
            double p = -SPAN + (2.0 * SPAN) * i / div;
            floor.getChildren().add(line(-SPAN, p, SPAN, p, grid));
            floor.getChildren().add(line(p, -SPAN, p, SPAN, grid));
        }
        floor.getChildren().add(line(-SPAN, SPAN, SPAN, SPAN, Color.web("#3c5b86")));
        floor.getChildren().add(line(-SPAN, -SPAN, -SPAN, SPAN, Color.web("#3c5b86")));

        // X ticks: stream counts, along the front edge.
        for (int i = 0; i < xs.size(); i++) {
            Text t = flatText(String.valueOf(xs.get(i)), "#9fb3d1", 22);
            t.setTranslateX(axisPos(i, xs.size()));
            t.setTranslateZ(SPAN + 42);
            floor.getChildren().add(t);
        }
        if (!xs.isEmpty()) {
            Text lbl = flatText("streams", "#5b7392", 20);
            lbl.setTranslateX(0);
            lbl.setTranslateZ(SPAN + 90);
            floor.getChildren().add(lbl);
        }
        // Z labels: configuration per row, along the left edge. Kept reading
        // left-to-right (no extra rotation) — turning them to follow the axis
        // made them run diagonally away from the viewer and become unreadable.
        for (int i = 0; i < zs.size(); i++) {
            Text t = flatText(shortConfig(zs.get(i)), "#9fb3d1", 20);
            t.setTranslateX(-SPAN - 110);
            t.setTranslateZ(axisPos(i, zs.size()));
            floor.getChildren().add(t);
        }
        return floor;
    }

    /** Compact form of a config key, so axis labels stay legible. */
    private static String shortConfig(String key) {
        return key.replace(" · Selector", " ·Sel")
                  .replace(" · Threaded", " ·Thr")
                  .replace(" (TLS 1.3)", "")
                  .replace("TCP + SACK", "SACK");
    }

    /** Text laid flat on the floor plane so it reads as an axis tick. */
    private Text flatText(String s, String color, double size) {
        Text t = new Text(s);
        t.setFill(Color.web(color));
        t.setFont(Font.font("System", FontWeight.BOLD, size));
        t.setTextOrigin(javafx.geometry.VPos.CENTER);
        t.setTranslateY(-2);
        // Centre it on its anchor, then lay it down onto the floor.
        t.setTranslateX(t.getTranslateX() - t.getLayoutBounds().getWidth() / 2);
        // Face up, so the top-down camera reads it un-mirrored.
        t.getTransforms().add(0, new Rotate(-90, Rotate.X_AXIS));
        return t;
    }

    private Box line(double x1, double z1, double x2, double z2, Color c) {
        double dx = x2 - x1, dz = z2 - z1;
        double len = Math.hypot(dx, dz);
        Box b = new Box(len == 0 ? 1 : len, 1.0, 1.0);
        b.setMaterial(mat(c));
        b.setTranslateX((x1 + x2) / 2);
        b.setTranslateZ((z1 + z2) / 2);
        if (dz != 0 && dx == 0) b.getTransforms().add(new Rotate(90, Rotate.Y_AXIS));
        return b;
    }

    /** Evenly spread `n` slots across the floor, centred. */
    private double axisPos(int i, int n) {
        if (n <= 1) return 0;
        double t = i / (double) (n - 1);
        return -SPAN * 0.78 + t * (SPAN * 1.56);
    }

    private void addBar(RunRecord r, List<Integer> xs, List<String> zs, double maxTput, boolean best) {
        double tput = r.getSummary().peakMbps();
        double height = Math.max(6, (tput / maxTput) * MAX_BAR);
        double x = axisPos(xs.indexOf(r.scenario.threads), xs.size());
        double z = axisPos(zs.indexOf(configKey(r.scenario)), zs.size());
        double side = Math.min(46, (SPAN * 1.6) / Math.max(3, xs.size() * 1.4));

        boolean isSel = r == selected.get();
        Color base = r.color;
        PhongMaterial m = new PhongMaterial(isSel || best ? base.brighter() : base);
        m.setSpecularColor(base.brighter());
        m.setSpecularPower(24);

        Box bar = new Box(side, height, side);
        bar.setMaterial(m);
        bar.setTranslateX(x);
        bar.setTranslateZ(z);
        bar.setTranslateY(-height / 2);
        content.getChildren().add(bar);

        // Bright cap so the top edge (the value) reads clearly.
        Box cap = new Box(side + 5, 3.5, side + 5);
        cap.setMaterial(mat(base.brighter()));
        cap.setTranslateX(x);
        cap.setTranslateZ(z);
        cap.setTranslateY(-height - 1.5);
        content.getChildren().add(cap);

        // Value floating just above the winner, so the optimum is unmissable.
        if (best || isSel) {
            Text v = new Text(fmt(tput));
            v.setFill(Color.web(best ? "#5eead4" : "#e2e8f0"));
            v.setFont(Font.font("System", FontWeight.BOLD, 22));
            v.setTextOrigin(javafx.geometry.VPos.CENTER);
            v.setTranslateX(x - v.getLayoutBounds().getWidth() / 2);
            v.setTranslateY(-height - 26);
            v.setTranslateZ(z);
            content.getChildren().add(v);
        }
    }

    private static String fmt(double mbps) {
        if (mbps >= 1000) return String.format("%.1fG", mbps / 1000);
        return String.format("%.0fM", mbps);
    }

    private PhongMaterial mat(Color c) {
        PhongMaterial m = new PhongMaterial(c);
        m.setSpecularColor(c.brighter());
        return m;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    @Override protected double computePrefWidth(double h) { return 640; }
    @Override protected double computePrefHeight(double w) { return 460; }
}

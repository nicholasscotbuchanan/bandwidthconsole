package com.bwtest.console.ui;

import com.bwtest.console.model.RunRecord;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.ObjectProperty;
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
import javafx.scene.shape.CullFace;
import javafx.scene.shape.DrawMode;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.transform.Rotate;
import javafx.util.Duration;

/**
 * Shared scaffolding for the real-time 3D views: sub-scene, orbit/zoom camera,
 * floor grid, flat axis text, and a 4 Hz refresh clock that only ticks while
 * the pane is actually in a scene. Subclasses implement {@link #rebuild} and
 * draw into {@link #content}.
 *
 * The run on display is the most recently started run that is still RUNNING;
 * with nothing live it falls back to the selected run, then the latest run —
 * so the views show the current test while one is up and the freshest result
 * afterwards.
 */
abstract class Live3DPane extends StackPane {

    protected static final double SPAN = 240;    // half-extent of the floor
    protected static final double MAX_BAR = 230; // tallest bar, world units

    protected final ObservableList<RunRecord> runs;
    protected final ObjectProperty<RunRecord> selected;
    protected final Group content = new Group();
    protected final Label caption = new Label();

    /** Full manual camera: orbit/pan/zoom mouse handling plus the control bar. */
    private final CameraRig rig = new CameraRig(-45, 35.264, -45, 3400, -100);

    protected Live3DPane(String title, String axesHint,
                         ObservableList<RunRecord> runs, ObjectProperty<RunRecord> selected) {
        this.runs = runs;
        this.selected = selected;
        getStyleClass().add("panel");
        setMinSize(300, 280);

        Group root3d = new Group(content);
        root3d.getChildren().addAll(
                new AmbientLight(Color.web("#4a5570")),
                pointLight(-600, -900, -700, "#e8f0ff"),
                pointLight(700, -400, 500, "#3a5f7a"));

        SubScene sub = new SubScene(root3d, 400, 300, true, SceneAntialiasing.BALANCED);
        sub.setFill(Color.web("#060a11"));
        sub.widthProperty().bind(widthProperty());
        sub.heightProperty().bind(heightProperty());
        rig.attach(sub, content);

        Label t = new Label(title);
        t.getStyleClass().add("panel-title");
        Label ax = new Label(axesHint);
        ax.getStyleClass().add("hint");
        caption.getStyleClass().add("hint");
        VBox overlay = new VBox(2, t, ax, caption);
        overlay.setMouseTransparent(true);
        StackPane.setAlignment(overlay, Pos.TOP_LEFT);
        StackPane.setMargin(overlay, new Insets(12));

        var cam = rig.controls();
        StackPane.setAlignment(cam, Pos.BOTTOM_LEFT);
        StackPane.setMargin(cam, new Insets(10));

        getChildren().addAll(sub, overlay, cam);

        // Refresh clock: cheap enough at 4 Hz, and stopped when not displayed.
        Timeline clock = new Timeline(new KeyFrame(Duration.millis(250), e -> refresh()));
        clock.setCycleCount(Animation.INDEFINITE);
        sceneProperty().addListener((o, a, s) -> {
            if (s == null) clock.stop(); else clock.play();
        });
        refresh();
    }

    private void refresh() {
        content.getChildren().clear();
        rebuild(pickRun());
    }

    /** Draw the view for `run` (may be null when nothing has run yet). */
    protected abstract void rebuild(RunRecord run);

    /** Live run first, then selection, then the most recent run. */
    protected RunRecord pickRun() {
        for (int i = runs.size() - 1; i >= 0; i--) {
            if (runs.get(i).getState() == RunRecord.State.RUNNING) return runs.get(i);
        }
        if (selected.get() != null) return selected.get();
        return runs.isEmpty() ? null : runs.get(runs.size() - 1);
    }

    // --- drawing helpers (shared look with Surface3D) ---

    protected Group buildFloor() {
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
        return floor;
    }

    protected Box line(double x1, double z1, double x2, double z2, Color c) {
        double dx = x2 - x1, dz = z2 - z1;
        double len = Math.hypot(dx, dz);
        Box b = new Box(len == 0 ? 1 : len, 1.0, 1.0);
        b.setMaterial(mat(c));
        b.setTranslateX((x1 + x2) / 2);
        b.setTranslateZ((z1 + z2) / 2);
        if (dz != 0 && dx == 0) b.getTransforms().add(new Rotate(90, Rotate.Y_AXIS));
        return b;
    }

    /** Text laid flat on the floor plane so it reads as an axis tick. */
    protected Text flatText(String s, String color, double size) {
        Text t = new Text(s);
        t.setFill(Color.web(color));
        t.setFont(Font.font("System", FontWeight.BOLD, size));
        t.setTextOrigin(javafx.geometry.VPos.CENTER);
        t.setTranslateY(-2);
        t.setTranslateX(-t.getLayoutBounds().getWidth() / 2);
        // Lay the text down facing UP, so it reads correctly from the
        // top-down camera.
        t.getTransforms().add(0, new Rotate(-90, Rotate.X_AXIS));
        return t;
    }

    /** Evenly spread `n` slots across the floor extent, centred. */
    protected double axisPos(int i, int n) {
        if (n <= 1) return 0;
        return -SPAN * 0.82 + (i / (double) (n - 1)) * (SPAN * 1.64);
    }

    /** One value bar with a bright cap, footprint `side`, at (x, z). */
    protected void addBar(double x, double z, double height, double side, Color base) {
        double h = Math.max(3, height);
        PhongMaterial m = new PhongMaterial(base);
        m.setSpecularColor(base.brighter());
        m.setSpecularPower(24);
        Box bar = new Box(side, h, side);
        bar.setMaterial(m);
        bar.setTranslateX(x);
        bar.setTranslateZ(z);
        bar.setTranslateY(-h / 2);
        Box cap = new Box(side + 3, 2.5, side + 3);
        cap.setMaterial(mat(base.brighter()));
        cap.setTranslateX(x);
        cap.setTranslateZ(z);
        cap.setTranslateY(-h - 1.2);
        content.getChildren().addAll(bar, cap);
    }

    /**
     * A continuous surface over a height grid: `h[iz][ix]` in world units (0 =
     * floor), spread across [xMin,xMax] × [zMin,zMax]. Returns the filled mesh;
     * callers add it (and optionally {@link #wire}) to {@link #content}.
     */
    protected MeshView surface(double[][] h, double xMin, double xMax,
                               double zMin, double zMax, Color c) {
        MeshView mv = new MeshView(buildMesh(h, xMin, xMax, zMin, zMax));
        PhongMaterial m = new PhongMaterial(c);
        m.setSpecularColor(c.brighter());
        m.setSpecularPower(28);
        mv.setMaterial(m);
        mv.setCullFace(CullFace.NONE);
        return mv;
    }

    /** Wireframe overlay of the same grid, so the mesh rows stay readable. */
    protected MeshView wire(double[][] h, double xMin, double xMax,
                            double zMin, double zMax, Color c) {
        MeshView mv = new MeshView(buildMesh(h, xMin, xMax, zMin, zMax));
        mv.setMaterial(new PhongMaterial(c));
        mv.setDrawMode(DrawMode.LINE);
        mv.setCullFace(CullFace.NONE);
        // Nudge above the fill so lines don't z-fight with it.
        mv.setTranslateY(-0.8);
        return mv;
    }

    private static TriangleMesh buildMesh(double[][] h, double xMin, double xMax,
                                          double zMin, double zMax) {
        int nz = h.length, nx = h[0].length;
        TriangleMesh mesh = new TriangleMesh();
        for (int iz = 0; iz < nz; iz++) {
            for (int ix = 0; ix < nx; ix++) {
                float x = (float) (nx == 1 ? xMin : xMin + (xMax - xMin) * ix / (nx - 1));
                float z = (float) (nz == 1 ? zMin : zMin + (zMax - zMin) * iz / (nz - 1));
                mesh.getPoints().addAll(x, (float) -h[iz][ix], z);
            }
        }
        mesh.getTexCoords().addAll(0, 0);
        for (int iz = 0; iz < nz - 1; iz++) {
            for (int ix = 0; ix < nx - 1; ix++) {
                int p00 = iz * nx + ix, p10 = p00 + 1, p01 = p00 + nx, p11 = p01 + 1;
                // Wound so face normals point up (toward the lights).
                mesh.getFaces().addAll(p00, 0, p10, 0, p01, 0);
                mesh.getFaces().addAll(p10, 0, p11, 0, p01, 0);
            }
        }
        return mesh;
    }

    private PointLight pointLight(double x, double y, double z, String col) {
        PointLight l = new PointLight(Color.web(col));
        l.setTranslateX(x);
        l.setTranslateY(y);
        l.setTranslateZ(z);
        return l;
    }

    protected PhongMaterial mat(Color c) {
        PhongMaterial m = new PhongMaterial(c);
        m.setSpecularColor(c.brighter());
        return m;
    }

    protected static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /**
     * Scale ceiling that ignores outliers: 95th percentile with headroom.
     * The first samples of a run count the socket-buffer fill burst and read
     * far above line rate; scaling to that spike squashes the steady-state
     * data the view exists to show. Values above the ceiling get clamped by
     * the caller (they are the artifact, not the signal).
     */
    protected static double robustMax(java.util.List<Double> vals) {
        if (vals.isEmpty()) return 1;
        java.util.List<Double> s = new java.util.ArrayList<>(vals);
        java.util.Collections.sort(s);
        double p95 = s.get((int) Math.floor((s.size() - 1) * 0.95));
        return Math.max(p95 * 1.12, 1e-9);
    }

    protected static String fmt(double mbps) {
        if (mbps >= 1000) return String.format("%.1fG", mbps / 1000);
        return String.format("%.0fM", mbps);
    }

    @Override protected double computePrefWidth(double h) { return 480; }
    @Override protected double computePrefHeight(double w) { return 420; }
}

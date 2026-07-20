package com.bwtest.console.ui;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.PerspectiveCamera;
import javafx.scene.SubScene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.transform.Rotate;

/**
 * Full manual camera for the 3D views.
 *
 * Mouse: left-drag orbits (unclamped — go under the floor if you want),
 * right- or shift-drag pans, scroll zooms. The overlay bar exposes all three
 * rotation axes (yaw / pitch / roll) plus zoom as sliders, and presets:
 * ISO (top-down isometric), TOP (plan view), FRONT (elevation), and RESET
 * back to this view's default. ISO and RESET restore the view's default roll;
 * TOP and FRONT are canonical orientations, so they always level it to 0.
 *
 * One rig per view; every view starts from the default handed to the
 * constructor, so each chart can pick its own home orientation.
 */
class CameraRig {

    final Rotate rotX = new Rotate(0, Rotate.X_AXIS);
    final Rotate rotY = new Rotate(0, Rotate.Y_AXIS);
    final Rotate rotZ = new Rotate(0, Rotate.Z_AXIS);
    final PerspectiveCamera camera = new PerspectiveCamera(true);

    private final DoubleProperty distance = new SimpleDoubleProperty();
    private final double defYaw, defPitch, defRoll, defDist, defY;

    private double anchorX, anchorY, anchorRX, anchorRY, anchorTX, anchorTY;

    CameraRig(double defaultYaw, double defaultPitch, double defaultRoll,
              double defaultDistance, double defaultY) {
        this.defYaw = defaultYaw;
        this.defPitch = defaultPitch;
        this.defRoll = defaultRoll;
        this.defDist = defaultDistance;
        this.defY = defaultY;

        camera.setNearClip(0.1);
        camera.setFarClip(20000);
        camera.setFieldOfView(11);
        camera.translateZProperty().bind(distance.negate());
        reset();
    }

    /** Attach mouse orbit/pan/zoom to the sub-scene rendering `content`. */
    void attach(SubScene sub, Group content) {
        content.getTransforms().addAll(rotZ, rotY, rotX);
        sub.setCamera(camera);

        sub.setOnMousePressed(e -> {
            anchorX = e.getSceneX();
            anchorY = e.getSceneY();
            anchorRX = rotX.getAngle();
            anchorRY = rotY.getAngle();
            anchorTX = camera.getTranslateX();
            anchorTY = camera.getTranslateY();
        });
        sub.setOnMouseDragged(e -> {
            boolean pan = e.getButton() == MouseButton.SECONDARY
                    || e.getButton() == MouseButton.MIDDLE || e.isShiftDown();
            double dx = e.getSceneX() - anchorX, dy = e.getSceneY() - anchorY;
            if (pan) {
                double k = distance.get() / 900.0; // scale pan to zoom level
                camera.setTranslateX(anchorTX - dx * k);
                camera.setTranslateY(anchorTY - dy * k);
            } else {
                rotY.setAngle(anchorRY + dx * 0.35);
                rotX.setAngle(anchorRX + dy * 0.35);
            }
        });
        sub.setOnScroll(e ->
                distance.set(clamp(distance.get() - e.getDeltaY() * 3.0, 500, 9000)));
    }

    /** The overlay control bar: yaw / pitch / roll / zoom sliders and view presets. */
    VBox controls() {
        Slider yaw = slider(-180, 180, rotY.angleProperty());
        Slider pitch = slider(-90, 90, rotX.angleProperty());
        Slider roll = slider(-180, 180, rotZ.angleProperty());
        Slider zoom = slider(500, 9000, distance);

        HBox row1 = new HBox(8, tag("yaw"), yaw, tag("pitch"), pitch);
        row1.setAlignment(Pos.CENTER_LEFT);
        HBox row2 = new HBox(8, tag("roll"), roll, tag("zoom"), zoom);
        row2.setAlignment(Pos.CENTER_LEFT);

        HBox presets = new HBox(6,
                preset("ISO", () -> view(defYaw, defPitch, defRoll, defDist)),
                preset("TOP", () -> view(0, 89.9, 0, defDist)),
                preset("FRONT", () -> view(0, 8, 0, defDist)),
                preset("RESET", this::reset));
        presets.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(4, row1, row2, presets);
        box.setPadding(new Insets(6, 8, 6, 8));
        box.getStyleClass().add("cam-bar");
        box.setPickOnBounds(false);
        // Shrink-wrap: without this the bar stretches over the whole pane.
        box.setMaxWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        box.setMaxHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        return box;
    }

    private void view(double yawDeg, double pitchDeg, double rollDeg, double dist) {
        rotY.setAngle(yawDeg);
        rotX.setAngle(pitchDeg);
        rotZ.setAngle(rollDeg);
        distance.set(dist);
        camera.setTranslateX(0);
        camera.setTranslateY(defY);
    }

    void reset() {
        view(defYaw, defPitch, defRoll, defDist);
    }

    private Slider slider(double min, double max, DoubleProperty prop) {
        Slider s = new Slider(min, max, prop.get());
        s.setPrefWidth(92);
        s.valueProperty().bindBidirectional(prop);
        return s;
    }

    private Label tag(String t) {
        Label l = new Label(t);
        l.getStyleClass().add("hint");
        return l;
    }

    private Button preset(String name, Runnable r) {
        Button b = new Button(name);
        b.getStyleClass().add("cam-btn");
        b.setFocusTraversable(false);
        b.setOnAction(e -> r.run());
        return b;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}

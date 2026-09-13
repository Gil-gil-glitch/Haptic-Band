package com.example.hapticband;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.PerspectiveCamera;
import javafx.scene.Scene;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.Sphere;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Translate;
import javafx.scene.AmbientLight;
import javafx.scene.PointLight;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class MainApp_2 extends Application {

    private static final int COMMAND_SERVER_PORT = 5050;

    private final SerialManager serialManager = new SerialManager();
    private CommandServer commandServer;

    private final MotorGauge topGauge    = new MotorGauge("TOP");
    private final MotorGauge rightGauge  = new MotorGauge("RIGHT");
    private final MotorGauge bottomGauge = new MotorGauge("BOTTOM");
    private final MotorGauge leftGauge   = new MotorGauge("LEFT");

    private final Slider topSlider    = new Slider(0, 255, 0);
    private final Slider rightSlider  = new Slider(0, 255, 0);
    private final Slider bottomSlider = new Slider(0, 255, 0);
    private final Slider leftSlider   = new Slider(0, 255, 0);

    // ── 3-D rotation state (driven by Mock-IMU sliders or live data later) ──
    private final Rotate rotateX = new Rotate(0, Rotate.X_AXIS);
    private final Rotate rotateY = new Rotate(0, Rotate.Y_AXIS);
    private final Rotate rotateZ = new Rotate(0, Rotate.Z_AXIS);

    // ── Motor spheres ──
    private final Sphere topSphere    = new Sphere(7);
    private final Sphere rightSphere  = new Sphere(7);
    private final Sphere bottomSphere = new Sphere(7);
    private final Sphere leftSphere   = new Sphere(7);

    // ── Motor materials  (idle = steel-blue accent, active = orange-red) ──
    private final PhongMaterial topMat    = makeMotorMaterial(Color.web("#1a1a2e"));
    private final PhongMaterial rightMat  = makeMotorMaterial(Color.web("#1a1a2e"));
    private final PhongMaterial bottomMat = makeMotorMaterial(Color.web("#1a1a2e"));
    private final PhongMaterial leftMat   = makeMotorMaterial(Color.web("#1a1a2e"));

    private final TextArea logArea               = new TextArea();
    private final Label    connectionStatusLabel = new Label("Disconnected");
    private final Label    serverStatusLabel     = new Label("Command server not started");
    private final CheckBox csvLogCheck           = new CheckBox("Log commands to CSV");

    private final ComboBox<String> portCombo    = new ComboBox<>();
    private final TextField        baudField    = new TextField("9600");
    private final Button           connectButton = new Button("Connect");

    private File   csvFile;
    private Writer csvWriter;
    private final DateTimeFormatter timestampFormat =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    // ── Inner class: pattern step ──────────────────────────────────────────
    public static class PatternStep {
        String direction;
        int    intensity;
        int    durationMs;

        public PatternStep(String direction, int intensity, int durationMs) {
            this.direction  = direction;
            this.intensity  = intensity;
            this.durationMs = durationMs;
        }

        @Override
        public String toString() {
            if (direction.equals("Pause (None)"))
                return "Pause for " + durationMs + " ms";
            return direction + " at " + intensity + " PWM for " + durationMs + " ms";
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Application entry point
    // ══════════════════════════════════════════════════════════════════════
    @Override
    public void start(Stage stage) {
        BorderPane root = new BorderPane();
        root.setTop(buildConnectionBar());
        root.setCenter(buildCenter());
        root.setRight(buildVisualPatternEditor());
        root.setBottom(buildBottom());
        root.setPadding(new Insets(15));
        root.getStyleClass().add("root-pane");

        // Wrap everything in a ScrollPane so that if the window is shorter
        // than the content (e.g. smaller displays), the user can scroll to
        // reach the 3-D visualization and every other panel instead of it
        // being cut off with no way to get to it.
        ScrollPane scrollRoot = new ScrollPane(root);
        scrollRoot.setFitToWidth(true);
        scrollRoot.getStyleClass().add("app-scroll");

        Scene scene = new Scene(scrollRoot, 1100, 820);

        // ── Inline CSS ─────────────────────────────────────────────────────
        String css = """
            .root-pane  { -fx-background-color:#1c1f26; -fx-font-family:'Segoe UI',sans-serif; }
            .app-scroll, .app-scroll>.viewport { -fx-background-color:#1c1f26; }
            .app-scroll .scroll-bar:vertical   { -fx-background-color:#1c1f26; }
            .label      { -fx-text-fill:#b9bbbe; }
            .button     { -fx-background-color:#2c2f33; -fx-text-fill:#ffffff;
                          -fx-border-color:#4f545c; -fx-border-radius:4px;
                          -fx-background-radius:4px; -fx-padding:6 12 6 12; -fx-cursor:hand; }
            .button:hover{ -fx-background-color:#40444b; -fx-border-color:#7289da; }
            .text-area,.list-view{ -fx-control-inner-background:#14161a;
                                   -fx-text-fill:#43b581; -fx-border-color:#4f545c; }
            .list-cell  { -fx-text-fill:#ffffff; }
            .list-cell:filled:selected:focused,.list-cell:filled:selected
                        { -fx-background-color:#7289da; -fx-text-fill:white; }
            .text-field { -fx-control-inner-background:#14161a;
                          -fx-text-fill:#ffffff; -fx-border-color:#4f545c; }
            .combo-box,.spinner{ -fx-background-color:#2c2f33; -fx-border-color:#4f545c; }
            .combo-box .list-cell{ -fx-text-fill:black; }
            .titled-pane>.title  { -fx-background-color:#23272a;
                                   -fx-text-fill:#ffffff; -fx-font-weight:bold; }
            .titled-pane>.content{ -fx-background-color:#1e2124;
                                   -fx-border-color:#1c1f26; }
            .compass-box  { -fx-border-color:#7289da; -fx-border-radius:8px;
                            -fx-background-color:#2c2f33; -fx-alignment:center; }
            .compass-label{ -fx-font-weight:bold; -fx-text-fill:#7289da; }
            .status-connected   { -fx-text-fill:#43b581; -fx-font-weight:bold; }
            .status-disconnected{ -fx-text-fill:#f04747; -fx-font-weight:bold; }
            .server-status      { -fx-font-style:italic; -fx-text-fill:#72767d; }
            .imu-test-btn { -fx-background-color:#5865f2; -fx-text-fill:white;
                            -fx-border-radius:4px; -fx-background-radius:4px;
                            -fx-font-weight:bold; -fx-cursor:hand; }
            .imu-test-btn:hover{ -fx-background-color:#7289da; }
            """;

        scene.getStylesheets().add(
                "data:text/css," + css.replace("\n","").replace(" ","%20"));

        stage.setTitle("Haptic Wristband Command Center");
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> shutdown());
        stage.show();

        refreshPorts();
        startCommandServer();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  UI builders
    // ══════════════════════════════════════════════════════════════════════

    private Node buildConnectionBar() {
        HBox bar = new HBox(12);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(0, 0, 20, 0));

        Button refreshButton = new Button("Refresh");
        refreshButton.setOnAction(e -> refreshPorts());

        baudField.setPrefWidth(80);
        connectButton.setOnAction(e -> toggleConnection());
        connectionStatusLabel.getStyleClass().add("status-disconnected");

        bar.getChildren().addAll(
                new Label("COM Port:"), portCombo, refreshButton,
                new Label("Baud Rate:"), baudField, connectButton,
                connectionStatusLabel);
        return bar;
    }

    private Node buildCenter() {
        VBox center = new VBox(10);
        center.setAlignment(Pos.TOP_CENTER);
        center.setPadding(new Insets(0, 15, 0, 0));

        GridPane cross = new GridPane();
        cross.setAlignment(Pos.CENTER);
        cross.setHgap(16);
        cross.setVgap(8);
        cross.add(wrap(topGauge),    1, 0);
        cross.add(wrap(leftGauge),   0, 1);
        cross.add(compassLabel(),    1, 1);
        cross.add(wrap(rightGauge),  2, 1);
        cross.add(wrap(bottomGauge), 1, 2);

        TitledPane crossPane = new TitledPane("Live Motor Status", cross);
        crossPane.setCollapsible(true);
        crossPane.setExpanded(true);

        center.getChildren().addAll(crossPane, buildManualControls(),
                buildPresets(), build3DView(),
                buildMockIMU());
        return center;
    }

    private Node wrap(MotorGauge gauge) {
        StackPane p = new StackPane(gauge);
        p.setPadding(new Insets(2));
        return p;
    }

    private Node compassLabel() {
        Label label = new Label("WRIST");
        label.getStyleClass().add("compass-label");
        StackPane pane = new StackPane(label);
        pane.setPrefSize(56, 56);
        pane.getStyleClass().add("compass-box");
        return pane;
    }

    // ── Manual sliders ────────────────────────────────────────────────────
    private Node buildManualControls() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(6);

        addSliderRow(grid, 0, "Top (Forward)",  topSlider);
        addSliderRow(grid, 1, "Right",           rightSlider);
        addSliderRow(grid, 2, "Bottom (Back)",   bottomSlider);
        addSliderRow(grid, 3, "Left",            leftSlider);

        Button sendButton = new Button("Apply Overrides");
        sendButton.setOnAction(e -> applyAndSend(
                (int) topSlider.getValue(), (int) rightSlider.getValue(),
                (int) bottomSlider.getValue(), (int) leftSlider.getValue(),
                "manual"));

        Button allOffButton = new Button("Halt All Motors");
        allOffButton.setStyle("-fx-border-color:#f04747;");
        allOffButton.setOnAction(e -> {
            topSlider.setValue(0); rightSlider.setValue(0);
            bottomSlider.setValue(0); leftSlider.setValue(0);
            applyAndSend(0, 0, 0, 0, "halt");
        });

        Button testButton = new Button("▶ Run Demo Sweep");
        testButton.setOnAction(e -> runTestSequence());

        HBox buttons = new HBox(10, sendButton, allOffButton, testButton);
        buttons.setAlignment(Pos.CENTER_LEFT);
        grid.add(buttons, 0, 4, 3, 1);

        TitledPane pane = new TitledPane("Manual Motor Controls", grid);
        pane.setCollapsible(true);
        pane.setExpanded(true);
        return pane;
    }

    private void addSliderRow(GridPane grid, int row, String name, Slider slider) {
        slider.setShowTickMarks(true);
        slider.setShowTickLabels(true);
        slider.setMajorTickUnit(64);
        slider.setPrefWidth(210);
        Label valueLabel = new Label("0");
        slider.valueProperty().addListener(
                (o, ov, nv) -> valueLabel.setText(String.valueOf(nv.intValue())));
        grid.addRow(row, new Label(name), slider, valueLabel);
    }

    // ── Presets ───────────────────────────────────────────────────────────
    private Node buildPresets() {
        HBox box = new HBox(10);
        box.setAlignment(Pos.CENTER_LEFT);

        String[][] presets = {
                {"↑ Forward",  "255,0,0,0"},
                {"→ Right",    "0,255,0,0"},
                {"↓ Back",     "0,0,255,0"},
                {"← Left",     "0,0,0,255"},
                {"All 50%",    "127,127,127,127"},
                {"All Off",    "0,0,0,0"}
        };
        for (String[] p : presets) {
            Button btn = new Button(p[0]);
            String[] vals = p[1].split(",");
            btn.setOnAction(e -> applyAndSend(
                    Integer.parseInt(vals[0]), Integer.parseInt(vals[1]),
                    Integer.parseInt(vals[2]), Integer.parseInt(vals[3]),
                    "preset"));
            box.getChildren().add(btn);
        }

        TitledPane pane = new TitledPane("Quick Presets", box);
        pane.setCollapsible(false);
        return pane;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  3-D Visualization  (REQUIREMENT 1 + 3)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Helper – create a PhongMaterial with specular highlight.
     */
    private static PhongMaterial makeMotorMaterial(Color diffuse) {
        PhongMaterial m = new PhongMaterial(diffuse);
        m.setSpecularColor(Color.web("#ffffff"));
        m.setSpecularPower(64);
        return m;
    }

    /**
     * Creates a 2-D Text label that lives inside the 3-D Group so it
     * rotates with the wristband.  Because JavaFX 3-D does not support
     * true 3-D text, we use a flat Text node with a billboard-style
     * translate so it sits just outside the cylinder surface.
     */
    private Text make3DLabel(String text, double tx, double ty, double tz) {
        Text t = new Text(text);
        t.setFont(Font.font("Segoe UI", FontWeight.BOLD, 11));
        t.setFill(Color.web("#e3e5e8"));
        t.setTranslateX(tx);
        t.setTranslateY(ty);
        t.setTranslateZ(tz);
        return t;
    }

    /** Hollow wristband tube mesh (unchanged geometry, improved material). */
    private MeshView createHollowCylinder(double outerR, double innerR,
                                          double height, int divs) {
        TriangleMesh mesh = new TriangleMesh();
        mesh.getTexCoords().addAll(0, 0);

        for (int i = 0; i < divs; i++) {
            double angle = 2 * Math.PI * i / divs;
            float x = (float) Math.cos(angle);
            float z = (float) Math.sin(angle);
            mesh.getPoints().addAll(x * (float)outerR, (float)-height/2, z * (float)outerR);
            mesh.getPoints().addAll(x * (float)innerR, (float)-height/2, z * (float)innerR);
            mesh.getPoints().addAll(x * (float)outerR, (float) height/2, z * (float)outerR);
            mesh.getPoints().addAll(x * (float)innerR, (float) height/2, z * (float)innerR);
        }

        for (int i = 0; i < divs; i++) {
            int next = (i + 1) % divs;
            int to0=i*4, ti0=i*4+1, bo0=i*4+2, bi0=i*4+3;
            int to1=next*4, ti1=next*4+1, bo1=next*4+2, bi1=next*4+3;

            mesh.getFaces().addAll(to0,0,to1,0,bo0,0, to1,0,bo1,0,bo0,0);  // outer
            mesh.getFaces().addAll(ti1,0,ti0,0,bi1,0, ti0,0,bi0,0,bi1,0);  // inner
            mesh.getFaces().addAll(ti0,0,ti1,0,to0,0, ti1,0,to1,0,to0,0);  // top cap
            mesh.getFaces().addAll(bo0,0,bo1,0,bi0,0, bo1,0,bi1,0,bi0,0);  // bottom cap
        }

        MeshView view = new MeshView(mesh);
        view.setCullFace(CullFace.NONE);
        return view;
    }

    private Node build3DView() {

        // ── Band material: bright azure with a strong sheen ──────────────
        PhongMaterial bandMat = new PhongMaterial(Color.web("#2e8fef"));
        bandMat.setSpecularColor(Color.web("#d6ecff"));
        bandMat.setSpecularPower(20);

        MeshView wrist = createHollowCylinder(40, 32, 100, 48);
        wrist.setMaterial(bandMat);

        // ── Motor sphere materials already declared as fields ────────────
        topSphere.setMaterial(topMat);
        rightSphere.setMaterial(rightMat);
        bottomSphere.setMaterial(bottomMat);
        leftSphere.setMaterial(leftMat);

        // ── Sphere positions (cardinal points on outer surface) ──────────
        topSphere.setTranslateZ(-42);
        bottomSphere.setTranslateZ( 42);
        rightSphere.setTranslateX( 42);
        leftSphere.setTranslateX(-42);

        // ── REQUIREMENT 1: 3-D text labels anchored to each sphere ───────
        // Offsets push labels slightly beyond the sphere so they don't overlap.
        Text labelTop    = make3DLabel("Top",    -8,  -14, -52);
        Text labelBottom = make3DLabel("Bottom", -14,  -14,  50);
        Text labelRight  = make3DLabel("Right",   44,  -14,   0);
        Text labelLeft   = make3DLabel("Left",   -56,  -14,   0);

        // ── REQUIREMENT 3: Lighting ───────────────────────────────────────
        // Soft ambient so nothing is completely black.
        AmbientLight ambient = new AmbientLight(Color.web("#404060"));

        // Key light – warm white from upper-front-left
        PointLight keyLight = new PointLight(Color.web("#d0dff5"));
        keyLight.setTranslateX(-120);
        keyLight.setTranslateY(-150);
        keyLight.setTranslateZ(-200);

        // Fill light – cool blue-white from right
        PointLight fillLight = new PointLight(Color.web("#8090c0"));
        fillLight.setTranslateX( 200);
        fillLight.setTranslateY(  50);
        fillLight.setTranslateZ(-100);

        // Rim light – accent from behind/below
        PointLight rimLight = new PointLight(Color.web("#203060"));
        rimLight.setTranslateX(0);
        rimLight.setTranslateY(200);
        rimLight.setTranslateZ(150);

        // ── Assemble scene graph ──────────────────────────────────────────
        Group wristGroup = new Group(
                wrist,
                topSphere, rightSphere, bottomSphere, leftSphere,
                labelTop, labelBottom, labelRight, labelLeft,
                ambient, keyLight, fillLight, rimLight
        );

        // Lay cylinder horizontally (arm extending right), then a fixed
        // 3/4 presentation tilt so the TOP/RIGHT/BOTTOM/LEFT motors don't
        // line up behind one another from the camera (a pure side-on view
        // hides two of the four directly behind the other two). IMU
        // rotations are still applied on top of this base orientation.
        Rotate presentationTiltY = new Rotate(35, Rotate.Y_AXIS);
        Rotate presentationTiltX = new Rotate(-22, Rotate.X_AXIS);

        wristGroup.getTransforms().addAll(
                new Rotate(90, Rotate.Z_AXIS),
                presentationTiltY, presentationTiltX,
                rotateX, rotateY, rotateZ);

        PerspectiveCamera camera = new PerspectiveCamera(true);
        camera.setTranslateZ(-420);
        camera.setNearClip(0.1);
        camera.setFarClip(1200);

        SubScene subScene = new SubScene(wristGroup, 480, 260, true,
                SceneAntialiasing.BALANCED);
        subScene.setFill(Color.web("#14161a"));
        subScene.setCamera(camera);

        TitledPane pane = new TitledPane("3D Wristband Visualization", subScene);
        pane.setCollapsible(false);
        return pane;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Mock IMU Telemetry pane  (REQUIREMENT 2)
    // ══════════════════════════════════════════════════════════════════════
    private Node buildMockIMU() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(6);

        Slider pitchSlider = new Slider(-180, 180, 0);
        Slider yawSlider   = new Slider(-180, 180, 0);
        Slider rollSlider  = new Slider(-180, 180, 0);

        for (Slider s : new Slider[]{pitchSlider, yawSlider, rollSlider}) {
            s.setPrefWidth(210);
            s.setShowTickLabels(true);
            s.setShowTickMarks(true);
            s.setMajorTickUnit(90);
        }

        // Bind sliders to the shared Rotate transforms
        pitchSlider.valueProperty().bindBidirectional(rotateX.angleProperty());
        yawSlider  .valueProperty().bindBidirectional(rotateY.angleProperty());
        rollSlider .valueProperty().bindBidirectional(rotateZ.angleProperty());

        // Live numeric readouts
        Label pitchVal = new Label("0°");
        Label yawVal   = new Label("0°");
        Label rollVal  = new Label("0°");

        pitchSlider.valueProperty().addListener(
                (o,ov,nv)-> pitchVal.setText(String.format("%.1f°", nv.doubleValue())));
        yawSlider  .valueProperty().addListener(
                (o,ov,nv)-> yawVal  .setText(String.format("%.1f°", nv.doubleValue())));
        rollSlider .valueProperty().addListener(
                (o,ov,nv)-> rollVal .setText(String.format("%.1f°", nv.doubleValue())));

        grid.addRow(0, new Label("Pitch (X)"), pitchSlider, pitchVal);
        grid.addRow(1, new Label("Yaw   (Y)"), yawSlider,   yawVal);
        grid.addRow(2, new Label("Roll  (Z)"), rollSlider,  rollVal);

        // ── REQUIREMENT 2: Test Tracking button ──────────────────────────
        Button testTrackBtn = new Button("▶ Test Tracking");
        testTrackBtn.getStyleClass().add("imu-test-btn");
        testTrackBtn.setOnAction(e -> runIMUTestSequence(
                pitchSlider, yawSlider, rollSlider, testTrackBtn));

        Button resetBtn = new Button("Reset to 0°");
        resetBtn.setOnAction(e -> {
            pitchSlider.setValue(0);
            yawSlider  .setValue(0);
            rollSlider .setValue(0);
        });

        HBox btnRow = new HBox(10, testTrackBtn, resetBtn);
        btnRow.setAlignment(Pos.CENTER_LEFT);
        grid.add(btnRow, 0, 3, 3, 1);

        TitledPane pane = new TitledPane("Mock IMU Telemetry", grid);
        pane.setCollapsible(true);
        pane.setExpanded(true);
        return pane;
    }

    /**
     * REQUIREMENT 2 – automated animation that smoothly moves pitch, yaw,
     * and roll so the developer can verify the 3-D model responds correctly
     * before live IMU data is wired in.
     *
     * Sequence:
     *   0 s  → reset to 0 / 0 / 0
     *   0–1 s → pitch +45°
     *   1–2 s → yaw   +90°
     *   2–3 s → roll  -60°
     *   3–4 s → all return to 0°
     */
    private void runIMUTestSequence(Slider pitch, Slider yaw,
                                    Slider roll, Button triggerBtn) {
        triggerBtn.setDisable(true);
        log("IMU tracking test started…");

        // Reset first
        pitch.setValue(0); yaw.setValue(0); roll.setValue(0);

        Timeline tl = new Timeline(
                // t=0  (already 0)
                new KeyFrame(Duration.ZERO,
                        new KeyValue(pitch.valueProperty(),  0),
                        new KeyValue(yaw  .valueProperty(),  0),
                        new KeyValue(roll .valueProperty(),  0)),

                // t=1 s – pitch up to +45°
                new KeyFrame(Duration.seconds(1),
                        new KeyValue(pitch.valueProperty(), 45)),

                // t=2 s – yaw right to +90°
                new KeyFrame(Duration.seconds(2),
                        new KeyValue(yaw.valueProperty(), 90)),

                // t=3 s – roll left to -60°
                new KeyFrame(Duration.seconds(3),
                        new KeyValue(roll.valueProperty(), -60)),

                // t=4 s – return everything to 0°
                new KeyFrame(Duration.seconds(4),
                        new KeyValue(pitch.valueProperty(),  0),
                        new KeyValue(yaw  .valueProperty(),  0),
                        new KeyValue(roll .valueProperty(),  0))
        );

        tl.setOnFinished(e -> {
            triggerBtn.setDisable(false);
            log("IMU tracking test complete.");
        });
        tl.play();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Visual pattern editor (right panel) – unchanged from original
    // ══════════════════════════════════════════════════════════════════════
    private Node buildVisualPatternEditor() {
        ObservableList<PatternStep> steps = FXCollections.observableArrayList();
        ListView<PatternStep> stepList = new ListView<>(steps);
        stepList.setPrefHeight(200);

        ComboBox<String> dirCombo = new ComboBox<>();
        dirCombo.getItems().addAll("Top","Right","Bottom","Left","Pause (None)");
        dirCombo.setValue("Top");

        Spinner<Integer> intensitySpinner = new Spinner<>(0, 255, 200, 5);
        intensitySpinner.setEditable(true);
        intensitySpinner.setPrefWidth(80);

        Spinner<Integer> durationSpinner = new Spinner<>(50, 5000, 300, 50);
        durationSpinner.setEditable(true);
        durationSpinner.setPrefWidth(80);

        Button addBtn    = new Button("Add Step");
        Button removeBtn = new Button("Remove Selected");
        Button runBtn    = new Button("▶ Run Pattern");
        Button saveBtn   = new Button("Save Pattern");
        Button loadBtn   = new Button("Load Pattern");

        addBtn.setOnAction(e -> {
            String dir = dirCombo.getValue();
            int    pw  = intensitySpinner.getValue();
            int    dur = durationSpinner.getValue();
            steps.add(new PatternStep(dir, pw, dur));
        });

        removeBtn.setOnAction(e -> {
            int idx = stepList.getSelectionModel().getSelectedIndex();
            if (idx >= 0) steps.remove(idx);
        });

        runBtn.setOnAction(e -> runPattern(steps, runBtn));
        saveBtn.setOnAction(e -> savePattern(steps));
        loadBtn.setOnAction(e -> loadPattern(steps));

        GridPane form = new GridPane();
        form.setHgap(8); form.setVgap(8);
        form.addRow(0, new Label("Direction:"), dirCombo);
        form.addRow(1, new Label("Intensity (PWM):"), intensitySpinner);
        form.addRow(2, new Label("Duration (ms):"),   durationSpinner);

        HBox patternButtons = new HBox(6, addBtn, removeBtn);
        HBox ioButtons      = new HBox(6, saveBtn, loadBtn);

        VBox box = new VBox(10, new Label("Steps:"), stepList, form,
                patternButtons, runBtn, ioButtons);
        box.setPadding(new Insets(0, 0, 0, 15));

        TitledPane pane = new TitledPane("Visual Pattern Sequencer", box);
        pane.setCollapsible(false);
        pane.setPrefWidth(320);
        return pane;
    }

    private void runPattern(ObservableList<PatternStep> steps, Button runBtn) {
        if (steps.isEmpty()) { log("Pattern is empty."); return; }
        runBtn.setDisable(true);
        log("Running pattern (" + steps.size() + " steps)…");

        List<PatternStep> copy = new ArrayList<>(steps);
        SequentialTransition seq = new SequentialTransition();

        for (PatternStep step : copy) {
            int[] vals = directionToValues(step.direction, step.intensity);
            PauseTransition pause = new PauseTransition(
                    Duration.millis(step.durationMs));
            pause.setOnFinished(e -> applyAndSend(
                    vals[0], vals[1], vals[2], vals[3], "pattern"));
            seq.getChildren().add(pause);
        }

        // Stop all motors after the last step finishes
        PauseTransition finalStop = new PauseTransition(Duration.millis(1));
        finalStop.setOnFinished(e -> applyAndSend(0, 0, 0, 0, "pattern-end"));
        seq.getChildren().add(finalStop);

        seq.setOnFinished(e -> {
            runBtn.setDisable(false);
            log("Pattern complete.");
        });
        seq.play();
    }

    private int[] directionToValues(String direction, int intensity) {
        return switch (direction) {
            case "Top"         -> new int[]{intensity, 0, 0, 0};
            case "Right"       -> new int[]{0, intensity, 0, 0};
            case "Bottom"      -> new int[]{0, 0, intensity, 0};
            case "Left"        -> new int[]{0, 0, 0, intensity};
            default            -> new int[]{0, 0, 0, 0};   // Pause
        };
    }

    private void savePattern(ObservableList<PatternStep> steps) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save Pattern");
        chooser.setInitialFileName("haptic-pattern.txt");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Text files","*.txt"));
        File file = chooser.showSaveDialog(logArea.getScene().getWindow());
        if (file == null) return;
        try (FileWriter fw = new FileWriter(file)) {
            for (PatternStep s : steps)
                fw.write(s.direction + "," + s.intensity + "," + s.durationMs + "\n");
            log("Pattern saved: " + file.getName());
        } catch (IOException ex) {
            log("Save failed: " + ex.getMessage());
        }
    }

    private void loadPattern(ObservableList<PatternStep> steps) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Load Pattern");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Text files","*.txt"));
        File file = chooser.showOpenDialog(logArea.getScene().getWindow());
        if (file == null) return;
        try {
            steps.clear();
            for (String line : Files.readAllLines(file.toPath())) {
                String[] parts = line.split(",");
                if (parts.length == 3)
                    steps.add(new PatternStep(
                            parts[0], Integer.parseInt(parts[1]),
                            Integer.parseInt(parts[2])));
            }
            log("Loaded pattern: " + file.getName());
        } catch (Exception ex) {
            log("Failed to load pattern. Check file format.");
        }
    }

    // ── Bottom log area ───────────────────────────────────────────────────
    private Node buildBottom() {
        logArea.setEditable(false);
        logArea.setPrefRowCount(6);

        csvLogCheck.setOnAction(e -> {
            if (csvLogCheck.isSelected()) chooseCsvFile();
            else closeCsvWriter();
        });

        serverStatusLabel.getStyleClass().add("server-status");

        VBox box = new VBox(8, new Label("System Log"), logArea,
                csvLogCheck, serverStatusLabel);
        box.setPadding(new Insets(20, 0, 0, 0));
        return box;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Serial link
    // ══════════════════════════════════════════════════════════════════════
    private void refreshPorts() {
        String selected = portCombo.getValue();
        portCombo.getItems().setAll(serialManager.listPorts());
        if (selected != null && portCombo.getItems().contains(selected))
            portCombo.setValue(selected);
        else if (!portCombo.getItems().isEmpty())
            portCombo.setValue(portCombo.getItems().get(0));
    }

    private void toggleConnection() {
        if (serialManager.isConnected()) {
            serialManager.disconnect();
            connectionStatusLabel.setText("Disconnected");
            connectionStatusLabel.getStyleClass().setAll("status-disconnected");
            connectButton.setText("Connect");
            log("Disconnected from serial port.");
            return;
        }

        String selectedPort = portCombo.getValue();
        if (selectedPort == null) { log("No serial port selected."); return; }

        int baud;
        try {
            baud = Integer.parseInt(baudField.getText().trim());
        } catch (NumberFormatException ex) {
            log("Invalid baud rate: " + baudField.getText()); return;
        }

        boolean connected = serialManager.connect(selectedPort, baud);
        if (connected) {
            connectionStatusLabel.setText("Connected: " + selectedPort + " @ " + baud);
            connectionStatusLabel.getStyleClass().setAll("status-connected");
            connectButton.setText("Disconnect");
            log("Connected to " + selectedPort + " at " + baud + " baud.");
        } else {
            connectionStatusLabel.setText("Failed to connect");
            connectionStatusLabel.getStyleClass().setAll("status-disconnected");
            log("Failed to open " + selectedPort + ".");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Command handling  (REQUIREMENT 3: sphere colour feedback)
    // ══════════════════════════════════════════════════════════════════════
    private void applyAndSend(int top, int right, int bottom, int left,
                              String source) {
        topGauge   .setPwm(top);
        rightGauge .setPwm(right);
        bottomGauge.setPwm(bottom);
        leftGauge  .setPwm(left);

        // Idle colour: steel-blue (#1a1a2e)   Active colour: vivid orange-red
        // Specular brightens on active to give a glowing effect
        updateMotorMaterial(topMat,    top);
        updateMotorMaterial(rightMat,  right);
        updateMotorMaterial(bottomMat, bottom);
        updateMotorMaterial(leftMat,   left);

        if (serialManager.isConnected()) {
            try {
                serialManager.sendMotorValues(top, right, bottom, left);
                log(String.format("[%s] Sent T=%d R=%d B=%d L=%d",
                        source, top, right, bottom, left));
            } catch (Exception ex) {
                log("Send failed: " + ex.getMessage());
            }
        } else {
            log(String.format("[%s] (not connected) T=%d R=%d B=%d L=%d",
                    source, top, right, bottom, left));
        }

        writeCsvRow(top, right, bottom, left, source);
    }

    /** Smoothly blends a motor sphere from steel-blue (idle) to orange-red (full). */
    private void updateMotorMaterial(PhongMaterial mat, int pwm) {
        double t = pwm / 255.0;
        Color idle   = Color.web("#1a1a2e");
        Color active = Color.web("#ff4500");   // OrangeRed – distinct from band blue
        mat.setDiffuseColor(idle.interpolate(active, t));
        // Specular highlight intensifies as motor activates
        mat.setSpecularColor(Color.web("#ffffff").interpolate(
                Color.web("#ff8c00"), t));
        mat.setSpecularPower(64 - t * 48);     // tighter highlight when bright
    }

    // ── Demo sweep ────────────────────────────────────────────────────────
    private void runTestSequence() {
        log("Running demo sweep…");
        int[][] steps = {
                {255,0,0,0}, {0,255,0,0}, {0,0,255,0}, {0,0,0,255}, {0,0,0,0}
        };
        applyAndSend(steps[0][0],steps[0][1],steps[0][2],steps[0][3],"sequence");
        SequentialTransition seq = new SequentialTransition();
        for (int i = 1; i < steps.length; i++) {
            int[] s = steps[i];
            PauseTransition p = new PauseTransition(Duration.millis(300));
            p.setOnFinished(e -> applyAndSend(s[0],s[1],s[2],s[3],"sequence"));
            seq.getChildren().add(p);
        }
        seq.setOnFinished(e -> log("Demo sweep complete."));
        seq.play();
    }

    // ── Command server ─────────────────────────────────────────────────────
    private void startCommandServer() {
        commandServer = new CommandServer(
                COMMAND_SERVER_PORT,
                (top,right,bottom,left) ->
                        applyAndSend(top,right,bottom,left,"external"),
                message -> Platform.runLater(() -> {
                    serverStatusLabel.setText(message);
                    log(message);
                })
        );
        commandServer.start();
    }

    // ── Logging ───────────────────────────────────────────────────────────
    private void log(String message) {
        String line = "[" + LocalDateTime.now().format(timestampFormat) + "] " + message;
        logArea.appendText(line + System.lineSeparator());
    }

    private void chooseCsvFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose CSV log file");
        chooser.setInitialFileName("haptic-session-" +
                LocalDateTime.now().format(
                        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".csv");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("CSV files","*.csv"));
        File chosen = chooser.showSaveDialog(logArea.getScene().getWindow());
        if (chosen == null) { csvLogCheck.setSelected(false); return; }
        csvFile = chosen;
        try {
            boolean isNew = !csvFile.exists() || Files.size(csvFile.toPath()) == 0;
            csvWriter = new FileWriter(csvFile, true);
            if (isNew) {
                csvWriter.write("timestamp,top,right,bottom,left,source\n");
                csvWriter.flush();
            }
            log("CSV logging enabled: " + csvFile.getAbsolutePath());
        } catch (IOException e) {
            log("Could not open CSV file: " + e.getMessage());
            csvLogCheck.setSelected(false);
        }
    }

    private void writeCsvRow(int top, int right, int bottom, int left,
                             String source) {
        if (!csvLogCheck.isSelected() || csvWriter == null) return;
        try {
            csvWriter.write(String.format("%s,%d,%d,%d,%d,%s%n",
                    LocalDateTime.now().format(timestampFormat),
                    top, right, bottom, left, source));
            csvWriter.flush();
        } catch (IOException e) {
            log("CSV write failed: " + e.getMessage());
        }
    }

    private void closeCsvWriter() {
        try {
            if (csvWriter != null) { csvWriter.close(); log("CSV logging stopped."); }
        } catch (IOException ignored) {}
        csvWriter = null;
    }

    private void shutdown() {
        applyAndSend(0, 0, 0, 0, "shutdown");
        if (commandServer != null) commandServer.stop();
        serialManager.disconnect();
        closeCsvWriter();
        Platform.exit();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
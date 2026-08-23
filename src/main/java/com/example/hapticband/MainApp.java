package com.example.hapticband;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
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

public class MainApp extends Application {

    private static final int COMMAND_SERVER_PORT = 5050;

    private final SerialManager serialManager = new SerialManager();
    private CommandServer commandServer;

    private final MotorGauge topGauge = new MotorGauge("TOP");
    private final MotorGauge rightGauge = new MotorGauge("RIGHT");
    private final MotorGauge bottomGauge = new MotorGauge("BOTTOM");
    private final MotorGauge leftGauge = new MotorGauge("LEFT");

    private final Slider topSlider = new Slider(0, 255, 0);
    private final Slider rightSlider = new Slider(0, 255, 0);
    private final Slider bottomSlider = new Slider(0, 255, 0);
    private final Slider leftSlider = new Slider(0, 255, 0);

    private final TextArea logArea = new TextArea();
    private final Label connectionStatusLabel = new Label("Disconnected");
    private final Label serverStatusLabel = new Label("Command server not started");
    private final CheckBox csvLogCheck = new CheckBox("Log commands to CSV");

    private final ComboBox<String> portCombo = new ComboBox<>();
    // Defaulted to 9600 for Arduino
    private final TextField baudField = new TextField("9600");
    private final Button connectButton = new Button("Connect");

    private File csvFile;
    private Writer csvWriter;
    private final DateTimeFormatter timestampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    // Inner class to represent a pattern step with exact PWM
    public static class PatternStep {
        String direction;
        int intensity; // Changed to int for raw PWM control
        int durationMs;

        public PatternStep(String direction, int intensity, int durationMs) {
            this.direction = direction;
            this.intensity = intensity;
            this.durationMs = durationMs;
        }

        @Override
        public String toString() {
            if (direction.equals("Pause (None)")) {
                return "Pause for " + durationMs + " ms";
            }
            return direction + " at " + intensity + " PWM for " + durationMs + " ms";
        }
    }

    @Override
    public void start(Stage stage) {
        BorderPane root = new BorderPane();
        root.setTop(buildConnectionBar());
        root.setCenter(buildCenter());
        root.setRight(buildVisualPatternEditor());
        root.setBottom(buildBottom());
        root.setPadding(new Insets(15));
        root.getStyleClass().add("root-pane");

        Scene scene = new Scene(root, 1050, 780);

        String css = """
        .root-pane { -fx-background-color: #23272a; -fx-font-family: 'Segoe UI', sans-serif; }
        .label { -fx-text-fill: #b9bbbe; }
        .button { -fx-background-color: #2c2f33; -fx-text-fill: #ffffff; -fx-border-color: #4f545c; -fx-border-radius: 4px; -fx-background-radius: 4px; -fx-padding: 6 12 6 12; -fx-cursor: hand; }
        .button:hover { -fx-background-color: #40444b; -fx-border-color: #7289da; }
        .text-area, .list-view { -fx-control-inner-background: #1e2124; -fx-text-fill: #43b581; -fx-border-color: #4f545c; }
        .list-cell { -fx-text-fill: #ffffff; }
        .list-cell:filled:selected:focused, .list-cell:filled:selected { -fx-background-color: #7289da; -fx-text-fill: white; }
        .text-field { -fx-control-inner-background: #1e2124; -fx-text-fill: #ffffff; -fx-border-color: #4f545c; }
        .combo-box, .spinner { -fx-background-color: #2c2f33; -fx-border-color: #4f545c; }
        .combo-box .list-cell { -fx-text-fill: black; }
        .titled-pane > .title { -fx-background-color: #2c2f33; -fx-text-fill: #ffffff; -fx-font-weight: bold; }
        .titled-pane > .content { -fx-background-color: #282b30; -fx-border-color: #23272a; }
        .compass-box { -fx-border-color: #7289da; -fx-border-radius: 8px; -fx-background-color: #2c2f33; -fx-alignment: center; }
        .compass-label { -fx-font-weight: bold; -fx-text-fill: #7289da; }
        .status-connected { -fx-text-fill: #43b581; -fx-font-weight: bold; }
        .status-disconnected { -fx-text-fill: #f04747; -fx-font-weight: bold; }
        .server-status { -fx-font-style: italic; -fx-text-fill: #72767d; }
        """;

        scene.getStylesheets().add("data:text/css," + css.replace("\n", "").replace(" ", "%20"));

        stage.setTitle("Haptic Wristband Command Center");
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> shutdown());
        stage.show();

        refreshPorts();
        startCommandServer();
    }

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
                connectionStatusLabel
        );
        return bar;
    }

    private Node buildCenter() {
        VBox center = new VBox(20);
        center.setAlignment(Pos.TOP_CENTER);
        center.setPadding(new Insets(0, 15, 0, 0));

        GridPane cross = new GridPane();
        cross.setAlignment(Pos.CENTER);
        cross.setHgap(30);
        cross.setVgap(15);
        cross.add(wrap(topGauge), 1, 0);
        cross.add(wrap(leftGauge), 0, 1);
        cross.add(compassLabel(), 1, 1);
        cross.add(wrap(rightGauge), 2, 1);
        cross.add(wrap(bottomGauge), 1, 2);

        TitledPane crossPane = new TitledPane("Live Motor Status", cross);
        crossPane.setCollapsible(false);

        center.getChildren().addAll(crossPane, buildManualControls(), buildPresets());
        return center;
    }

    private Node wrap(MotorGauge gauge) {
        StackPane pane = new StackPane(gauge);
        pane.setPadding(new Insets(5));
        return pane;
    }

    private Node compassLabel() {
        Label label = new Label("WRIST");
        label.getStyleClass().add("compass-label");
        StackPane pane = new StackPane(label);
        pane.setPrefSize(80, 80);
        pane.getStyleClass().add("compass-box");
        return pane;
    }

    private Node buildManualControls() {
        GridPane grid = new GridPane();
        grid.setHgap(15);
        grid.setVgap(12);

        addSliderRow(grid, 0, "Top (Forward)", topSlider);
        addSliderRow(grid, 1, "Right", rightSlider);
        addSliderRow(grid, 2, "Bottom (Back)", bottomSlider);
        addSliderRow(grid, 3, "Left", leftSlider);

        Button sendButton = new Button("Apply Overrides");
        sendButton.setOnAction(e -> applyAndSend(
                (int) topSlider.getValue(), (int) rightSlider.getValue(),
                (int) bottomSlider.getValue(), (int) leftSlider.getValue(), "manual"));

        Button allOffButton = new Button("Halt All Motors");
        allOffButton.setStyle("-fx-border-color: #f04747;");
        allOffButton.setOnAction(e -> {
            topSlider.setValue(0); rightSlider.setValue(0);
            bottomSlider.setValue(0); leftSlider.setValue(0);
            applyAndSend(0, 0, 0, 0, "manual");
        });

        HBox buttons = new HBox(12, sendButton, allOffButton);
        buttons.setPadding(new Insets(10, 0, 0, 0));

        VBox box = new VBox(8, grid, buttons);
        TitledPane pane = new TitledPane("Manual Overrides", box);
        pane.setCollapsible(false);
        return pane;
    }

    private void addSliderRow(GridPane grid, int row, String name, Slider slider) {
        slider.setPrefWidth(220);
        Label valueLabel = new Label("0");
        valueLabel.setPrefWidth(35);
        slider.valueProperty().addListener((obs, oldV, newV) ->
                valueLabel.setText(String.valueOf(newV.intValue())));

        Label nameLabel = new Label(name);
        nameLabel.setPrefWidth(95);

        grid.add(nameLabel, 0, row);
        grid.add(slider, 1, row);
        grid.add(valueLabel, 2, row);
    }

    private Node buildPresets() {
        Button pulseTop = new Button("Pulse Forward");
        pulseTop.setOnAction(e -> applyAndSend(255, 0, 0, 0, "preset"));

        Button allHalf = new Button("Global 50%");
        allHalf.setOnAction(e -> applyAndSend(127, 127, 127, 127, "preset"));

        Button runSequence = new Button("Run Demo Sweep");
        runSequence.setOnAction(e -> runTestSequence());

        FlowPane flow = new FlowPane(10, 10, pulseTop, allHalf, runSequence);
        TitledPane pane = new TitledPane("Quick Triggers", flow);
        pane.setCollapsible(false);
        return pane;
    }

    private Node buildVisualPatternEditor() {
        VBox layout = new VBox(12);
        layout.setPrefWidth(360);

        ComboBox<String> dirCombo = new ComboBox<>(FXCollections.observableArrayList(
                "Top (Forward)", "Right", "Bottom (Back)", "Left", "All Motors", "Pause (None)"
        ));
        dirCombo.setValue("Top (Forward)");
        dirCombo.setPrefWidth(120);

        // Replaced Preset ComboBox with a numeric Spinner for exact PWM
        Spinner<Integer> pwmSpinner = new Spinner<>(0, 255, 127, 5); // min, max, default, step
        pwmSpinner.setEditable(true);
        pwmSpinner.setPrefWidth(70);

        Spinner<Integer> durSpinner = new Spinner<>(50, 5000, 500, 50);
        durSpinner.setEditable(true);
        durSpinner.setPrefWidth(75);

        Button addBtn = new Button("Add");

        HBox builderRow1 = new HBox(8, new Label("Dir:"), dirCombo, new Label("PWM:"), pwmSpinner);
        builderRow1.setAlignment(Pos.CENTER_LEFT);
        HBox builderRow2 = new HBox(8, new Label("Time (ms):"), durSpinner, addBtn);
        builderRow2.setAlignment(Pos.CENTER_LEFT);

        ListView<PatternStep> stepList = new ListView<>();
        stepList.setPrefHeight(250);
        VBox.setVgrow(stepList, Priority.ALWAYS);

        addBtn.setOnAction(e -> {
            stepList.getItems().add(new PatternStep(dirCombo.getValue(), pwmSpinner.getValue(), durSpinner.getValue()));
        });

        Button removeBtn = new Button("Remove Selected");
        removeBtn.setOnAction(e -> {
            int selected = stepList.getSelectionModel().getSelectedIndex();
            if (selected >= 0) stepList.getItems().remove(selected);
        });

        Button clearBtn = new Button("Clear All");
        clearBtn.setOnAction(e -> stepList.getItems().clear());

        HBox editBtns = new HBox(8, removeBtn, clearBtn);

        Button playBtn = new Button("▶ Play Sequence");
        playBtn.setStyle("-fx-text-fill: #43b581; -fx-border-color: #43b581; -fx-font-weight: bold;");
        playBtn.setMaxWidth(Double.MAX_VALUE);
        playBtn.setOnAction(e -> playVisualPattern(stepList.getItems()));

        Button saveBtn = new Button("Save...");
        saveBtn.setOnAction(e -> saveVisualPattern(stepList.getItems()));

        Button loadBtn = new Button("Load...");
        loadBtn.setOnAction(e -> loadVisualPattern(stepList.getItems()));

        HBox fileBtns = new HBox(8, saveBtn, loadBtn);

        layout.getChildren().addAll(
                new Label("1. Construct Sequence Step-by-Step"),
                builderRow1, builderRow2,
                stepList,
                editBtns,
                new Label("2. Save / Execute"),
                fileBtns, playBtn
        );

        TitledPane pane = new TitledPane("Pattern Builder", layout);
        pane.setCollapsible(false);
        pane.setMaxHeight(Double.MAX_VALUE);
        return pane;
    }

    private void playVisualPattern(ObservableList<PatternStep> steps) {
        if (steps.isEmpty()) return;

        Timeline timeline = new Timeline();
        double currentDelayMs = 0;

        for (PatternStep step : steps) {
            // Now grabs the exact PWM value the user entered
            int pwm = step.intensity;

            int t, r, b, l;
            if (!step.direction.equals("Pause (None)")) {
                t = (step.direction.contains("Top") || step.direction.equals("All Motors")) ? pwm : 0;
                r = (step.direction.contains("Right") || step.direction.equals("All Motors")) ? pwm : 0;
                b = (step.direction.contains("Bottom") || step.direction.equals("All Motors")) ? pwm : 0;
                l = (step.direction.contains("Left") || step.direction.equals("All Motors")) ? pwm : 0;
            } else {
                t = 0; r = 0; b = 0; l = 0;
            }

            KeyFrame kf = new KeyFrame(Duration.millis(currentDelayMs),
                    e -> applyAndSend(t, r, b, l, "custom_sequence"));
            timeline.getKeyFrames().add(kf);

            currentDelayMs += step.durationMs;
        }

        KeyFrame endOff = new KeyFrame(Duration.millis(currentDelayMs),
                e -> applyAndSend(0, 0, 0, 0, "sequence_end"));
        timeline.getKeyFrames().add(endOff);

        log("Playing custom sequence (" + steps.size() + " steps)...");
        timeline.play();
    }

    private void saveVisualPattern(ObservableList<PatternStep> steps) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save Haptic Pattern");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Haptic Files", "*.hpt"));
        File file = chooser.showSaveDialog(null);
        if (file != null) {
            try {
                List<String> lines = new ArrayList<>();
                for (PatternStep step : steps) {
                    lines.add(step.direction + "," + step.intensity + "," + step.durationMs);
                }
                Files.write(file.toPath(), lines);
                log("Saved pattern to " + file.getName());
            } catch (IOException e) {
                log("Failed to save pattern: " + e.getMessage());
            }
        }
    }

    private void loadVisualPattern(ObservableList<PatternStep> steps) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Load Haptic Pattern");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Haptic Files", "*.hpt"));
        File file = chooser.showOpenDialog(null);
        if (file != null) {
            try {
                steps.clear();
                List<String> lines = Files.readAllLines(file.toPath());
                for (String line : lines) {
                    String[] parts = line.split(",");
                    if (parts.length == 3) {
                        steps.add(new PatternStep(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
                    }
                }
                log("Loaded pattern: " + file.getName());
            } catch (Exception e) {
                log("Failed to load pattern. Check file format.");
            }
        }
    }

    private Node buildBottom() {
        logArea.setEditable(false);
        logArea.setPrefRowCount(6);

        csvLogCheck.setOnAction(e -> {
            if (csvLogCheck.isSelected()) chooseCsvFile();
            else closeCsvWriter();
        });

        serverStatusLabel.getStyleClass().add("server-status");

        VBox box = new VBox(8, new Label("System Log"), logArea, csvLogCheck, serverStatusLabel);
        box.setPadding(new Insets(20, 0, 0, 0));
        return box;
    }

    // ------------------------------------------------------- Serial link
    private void refreshPorts() {
        String selected = portCombo.getValue();
        portCombo.getItems().setAll(serialManager.listPorts());
        if (selected != null && portCombo.getItems().contains(selected)) {
            portCombo.setValue(selected);
        } else if (!portCombo.getItems().isEmpty()) {
            portCombo.setValue(portCombo.getItems().get(0));
        }
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
        if (selectedPort == null) {
            log("No serial port selected.");
            return;
        }
        int baud;
        try {
            baud = Integer.parseInt(baudField.getText().trim());
        } catch (NumberFormatException ex) {
            log("Invalid baud rate: " + baudField.getText());
            return;
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

    // -------------------------------------------------- Command handling
    private void applyAndSend(int top, int right, int bottom, int left, String source) {
        topGauge.setPwm(top);
        rightGauge.setPwm(right);
        bottomGauge.setPwm(bottom);
        leftGauge.setPwm(left);

        if (serialManager.isConnected()) {
            try {
                serialManager.sendMotorValues(top, right, bottom, left);
                log(String.format("[%s] Sent T=%d R=%d B=%d L=%d", source, top, right, bottom, left));
            } catch (Exception ex) {
                log("Send failed: " + ex.getMessage());
            }
        } else {
            log(String.format("[%s] (not connected) T=%d R=%d B=%d L=%d", source, top, right, bottom, left));
        }

        writeCsvRow(top, right, bottom, left, source);
    }

    private void runTestSequence() {
        log("Running demo sweep...");
        int[][] steps = {
                {255, 0, 0, 0},
                {0, 255, 0, 0},
                {0, 0, 255, 0},
                {0, 0, 0, 255},
                {0, 0, 0, 0}
        };

        applyAndSend(steps[0][0], steps[0][1], steps[0][2], steps[0][3], "sequence");

        SequentialTransition seq = new SequentialTransition();
        for (int i = 1; i < steps.length; i++) {
            int[] step = steps[i];
            PauseTransition pause = new PauseTransition(Duration.millis(300));
            pause.setOnFinished(e -> applyAndSend(step[0], step[1], step[2], step[3], "sequence"));
            seq.getChildren().add(pause);
        }
        seq.setOnFinished(e -> log("Demo sweep complete."));
        seq.play();
    }

    // -------------------------------------------------------- Command server
    private void startCommandServer() {
        commandServer = new CommandServer(
                COMMAND_SERVER_PORT,
                (top, right, bottom, left) -> applyAndSend(top, right, bottom, left, "external"),
                message -> {
                    Platform.runLater(() -> {
                        serverStatusLabel.setText(message);
                        log(message);
                    });
                }
        );
        commandServer.start();
    }

    // -------------------------------------------------------------- Logging
    private void log(String message) {
        String line = "[" + LocalDateTime.now().format(timestampFormat) + "] " + message;
        logArea.appendText(line + System.lineSeparator());
    }

    private void chooseCsvFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose CSV log file");
        chooser.setInitialFileName("haptic-session-" +
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".csv");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV files", "*.csv"));

        File chosen = chooser.showSaveDialog(logArea.getScene().getWindow());
        if (chosen == null) {
            csvLogCheck.setSelected(false);
            return;
        }
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

    private void writeCsvRow(int top, int right, int bottom, int left, String source) {
        if (!csvLogCheck.isSelected() || csvWriter == null) return;
        try {
            String row = String.format("%s,%d,%d,%d,%d,%s%n",
                    LocalDateTime.now().format(timestampFormat), top, right, bottom, left, source);
            csvWriter.write(row);
            csvWriter.flush();
        } catch (IOException e) {
            log("CSV write failed: " + e.getMessage());
        }
    }

    private void closeCsvWriter() {
        try {
            if (csvWriter != null) {
                csvWriter.close();
                log("CSV logging stopped.");
            }
        } catch (IOException ignored) {
        }
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
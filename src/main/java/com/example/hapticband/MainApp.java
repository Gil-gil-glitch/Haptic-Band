package com.example.hapticband;

import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.application.Application;
import javafx.application.Platform;
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
    private final TextArea patternEditor = new TextArea(); // New: Pattern Editor

    private final Label connectionStatusLabel = new Label("Disconnected");
    private final Label serverStatusLabel = new Label("Command server not started");
    private final CheckBox csvLogCheck = new CheckBox("Log commands to CSV");

    private final ComboBox<String> portCombo = new ComboBox<>();
    private final TextField baudField = new TextField("9600"); // 115200 is for ESP32
    private final Button connectButton = new Button("Connect");

    private File csvFile;
    private Writer csvWriter;
    private final DateTimeFormatter timestampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    @Override
    public void start(Stage stage) {
        BorderPane root = new BorderPane();
        root.setTop(buildConnectionBar());

        // Split layout: Left for Controls, Right for Patterns Customization
        HBox mainContent = new HBox(15);
        mainContent.getChildren().addAll(buildLeftPanel(), buildRightPanel());
        HBox.setHgrow(mainContent.getChildren().get(1), Priority.ALWAYS);

        root.setCenter(mainContent);
        root.setBottom(buildBottom());
        root.setPadding(new Insets(15));
        root.getStyleClass().add("main-background");

        Scene scene = new Scene(root, 1050, 720);

        // Modern Dark Theme CSS
        String css = """
        .root { -fx-font-family: 'Segoe UI', Helvetica, Arial, sans-serif; }
        .main-background { -fx-background-color: #1e1e1e; }
        .label, .check-box { -fx-text-fill: #e0e0e0; }
        .status-connected { -fx-text-fill: #4caf50; -fx-font-weight: bold; }
        .status-disconnected { -fx-text-fill: #f44336; -fx-font-weight: bold; }
        .compass-box { -fx-border-color: #555555; -fx-border-radius: 8px; -fx-alignment: center; -fx-background-color: #2d2d2d; -fx-background-radius: 8px;}
        .compass-label { -fx-font-weight: bold; -fx-text-fill: #90caf9; -fx-font-size: 11px;}
        .server-status { -fx-font-style: italic; -fx-text-fill: #9e9e9e; }
        .titled-pane { -fx-text-fill: #e0e0e0; }
        .titled-pane > .title { -fx-background-color: #2d2d2d; -fx-border-color: #444; -fx-border-radius: 4px 4px 0 0; }
        .titled-pane > .content { -fx-background-color: #252525; -fx-border-color: #444; -fx-border-radius: 0 0 4px 4px; }
        .button { -fx-background-color: #3a3a3a; -fx-text-fill: white; -fx-background-radius: 4px; -fx-cursor: hand; -fx-padding: 6 12 6 12; }
        .button:hover { -fx-background-color: #505050; }
        .button-accent { -fx-background-color: #1976d2; -fx-font-weight: bold; }
        .button-accent:hover { -fx-background-color: #1565c0; }
        .text-area, .text-field { -fx-control-inner-background: #2b2b2b; -fx-text-fill: #e0e0e0; -fx-border-color: #444; }
        """;

        scene.getStylesheets().add("data:text/css," + css.replace("\n", "").replace(" ", "%20"));

        stage.setTitle("Haptic Wristband Studio");
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> shutdown());
        stage.show();

        refreshPorts();
        startCommandServer();
    }

    private Node buildConnectionBar() {
        HBox bar = new HBox(12);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(0, 0, 15, 0));

        Button refreshButton = new Button("Refresh Ports");
        refreshButton.setOnAction(e -> refreshPorts());
        baudField.setPrefWidth(80);
        connectButton.setOnAction(e -> toggleConnection());
        connectButton.getStyleClass().add("button-accent");
        connectionStatusLabel.getStyleClass().add("status-disconnected");

        bar.getChildren().addAll(
                new Label("COM Port:"), portCombo,
                refreshButton,
                new Label("Baud Rate:"), baudField,
                connectButton,
                connectionStatusLabel
        );
        return bar;
    }

    private Node buildLeftPanel() {
        VBox panel = new VBox(15);
        panel.setAlignment(Pos.TOP_CENTER);
        panel.setPrefWidth(400);

        GridPane cross = new GridPane();
        cross.setAlignment(Pos.CENTER);
        cross.setHgap(30);
        cross.setVgap(15);
        cross.add(wrap(topGauge), 1, 0);
        cross.add(wrap(leftGauge), 0, 1);
        cross.add(compassLabel(), 1, 1);
        cross.add(wrap(rightGauge), 2, 1);
        cross.add(wrap(bottomGauge), 1, 2);

        TitledPane crossPane = new TitledPane("Live Motor Output", cross);
        crossPane.setCollapsible(false);

        panel.getChildren().addAll(crossPane, buildManualControls());
        return panel;
    }

    private Node buildRightPanel() {
        VBox panel = new VBox(15);

        // Setup Pattern Editor
        patternEditor.setPrefRowCount(12);
        patternEditor.setStyle("-fx-font-family: 'Consolas', monospace;");
        patternEditor.setText("""
        # Format: Top,Right,Bottom,Left,DurationMs
        # (Values 0-255, Duration in milliseconds)
        255,0,0,0,300
        0,255,0,0,300
        0,0,255,0,300
        0,0,0,255,300
        0,0,0,0,500
        128,128,128,128,500
        """);

        Button playPattern = new Button("▶ Play Sequence");
        playPattern.getStyleClass().add("button-accent");
        playPattern.setOnAction(e -> playCustomPattern());

        Button loadBtn = new Button("Load...");
        loadBtn.setOnAction(e -> loadPattern());

        Button saveBtn = new Button("Save...");
        saveBtn.setOnAction(e -> savePattern());

        HBox patternControls = new HBox(8, playPattern, loadBtn, saveBtn);
        patternControls.setAlignment(Pos.CENTER_LEFT);

        VBox patternBox = new VBox(8, new Label("Sequence Editor"), patternEditor, patternControls);
        TitledPane customPane = new TitledPane("Custom Pattern Studio", patternBox);
        customPane.setCollapsible(false);

        panel.getChildren().addAll(customPane, buildPresets());
        return panel;
    }

    private Node wrap(MotorGauge gauge) {
        StackPane pane = new StackPane(gauge);
        pane.setPadding(new Insets(4));
        return pane;
    }

    private Node compassLabel() {
        Label label = new Label("BAND\nTOP ⇧");
        label.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
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

        addSliderRow(grid, 0, "Top", topSlider);
        addSliderRow(grid, 1, "Right", rightSlider);
        addSliderRow(grid, 2, "Bottom", bottomSlider);
        addSliderRow(grid, 3, "Left", leftSlider);

        Button sendButton = new Button("Send Current Sliders");
        sendButton.setOnAction(e -> applyAndSend(
                (int) topSlider.getValue(), (int) rightSlider.getValue(),
                (int) bottomSlider.getValue(), (int) leftSlider.getValue(), "manual"));

        Button allOffButton = new Button("Force All Off");
        allOffButton.setOnAction(e -> {
            topSlider.setValue(0); rightSlider.setValue(0);
            bottomSlider.setValue(0); leftSlider.setValue(0);
            applyAndSend(0, 0, 0, 0, "manual");
        });

        HBox buttons = new HBox(10, sendButton, allOffButton);
        buttons.setPadding(new Insets(10, 0, 0, 0));
        VBox box = new VBox(8, grid, buttons);
        TitledPane pane = new TitledPane("Manual Override", box);
        pane.setCollapsible(false);
        return pane;
    }

    private void addSliderRow(GridPane grid, int row, String name, Slider slider) {
        slider.setPrefWidth(220);
        Label valueLabel = new Label("0");
        valueLabel.setPrefWidth(30);
        slider.valueProperty().addListener((obs, oldV, newV) ->
                valueLabel.setText(String.valueOf(newV.intValue())));
        grid.add(new Label(name), 0, row);
        grid.add(slider, 1, row);
        grid.add(valueLabel, 2, row);
    }

    private Node buildPresets() {
        Button pulseTop = new Button("Pulse Top");
        pulseTop.setOnAction(e -> applyAndSend(255, 0, 0, 0, "preset"));

        Button pulseRight = new Button("Pulse Right");
        pulseRight.setOnAction(e -> applyAndSend(0, 255, 0, 0, "preset"));

        Button allHalf = new Button("All 50%");
        allHalf.setOnAction(e -> applyAndSend(127, 127, 127, 127, "preset"));

        Button allOff = new Button("All Off");
        allOff.setOnAction(e -> applyAndSend(0, 0, 0, 0, "preset"));

        FlowPane flow = new FlowPane(10, 10, pulseTop, pulseRight, allHalf, allOff);
        TitledPane pane = new TitledPane("Quick Tests", flow);
        pane.setCollapsible(false);
        return pane;
    }

    private Node buildBottom() {
        logArea.setEditable(false);
        logArea.setPrefRowCount(6);
        logArea.setStyle("-fx-font-family: 'Consolas', monospace;");

        csvLogCheck.setOnAction(e -> {
            if (csvLogCheck.isSelected()) chooseCsvFile();
            else closeCsvWriter();
        });
        serverStatusLabel.getStyleClass().add("server-status");

        HBox bottomControls = new HBox(15, csvLogCheck, serverStatusLabel);
        bottomControls.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(8, new Label("Activity Log"), logArea, bottomControls);
        box.setPadding(new Insets(15, 0, 0, 0));
        return box;
    }

    // Custom Pattern Logic that plays the pattern set by a user

    private void playCustomPattern() {
        String[] lines = patternEditor.getText().split("\\r?\\n");
        SequentialTransition sequence = new SequentialTransition();

        log("Compiling custom pattern...");

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            // Skip comments and empty lines
            if (line.isEmpty() || line.startsWith("#")) continue;

            String[] parts = line.split(",");
            if (parts.length >= 5) {
                try {
                    int t = Integer.parseInt(parts[0].trim());
                    int r = Integer.parseInt(parts[1].trim());
                    int b = Integer.parseInt(parts[2].trim());
                    int l = Integer.parseInt(parts[3].trim());
                    int duration = Integer.parseInt(parts[4].trim());

                    PauseTransition step = new PauseTransition(Duration.millis(duration));
                    step.setOnFinished(e -> applyAndSend(t, r, b, l, "custom"));
                    sequence.getChildren().add(step);
                } catch (NumberFormatException ex) {
                    log("Error parsing pattern on line " + (i + 1) + ": " + line);
                }
            }
        }

        // Safety feature: always turn off motors at the end of the sequence
        PauseTransition endStep = new PauseTransition(Duration.millis(10));
        endStep.setOnFinished(e -> applyAndSend(0, 0, 0, 0, "custom_end"));
        sequence.getChildren().add(endStep);

        sequence.setOnFinished(e -> log("Custom pattern finished."));
        sequence.play();
    }

    private void savePattern() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save Pattern Script");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Text Files", "*.txt"));
        File file = chooser.showSaveDialog(patternEditor.getScene().getWindow());

        if (file != null) {
            try {
                Files.writeString(file.toPath(), patternEditor.getText());
                log("Pattern saved to: " + file.getName());
            } catch (IOException e) {
                log("Failed to save pattern: " + e.getMessage());
            }
        }
    }

    private void loadPattern() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Load Pattern Script");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Text Files", "*.txt"));
        File file = chooser.showOpenDialog(patternEditor.getScene().getWindow());

        if (file != null) {
            try {
                String content = Files.readString(file.toPath());
                patternEditor.setText(content);
                log("Loaded pattern: " + file.getName());
            } catch (IOException e) {
                log("Failed to load pattern: " + e.getMessage());
            }
        }
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
            } catch (Exception ex) {
                log("Send failed: " + ex.getMessage());
            }
        }

        writeCsvRow(top, right, bottom, left, source);
    }

    // -------------------------------------------------------- Command server

    private void startCommandServer() {
        commandServer = new CommandServer(
                COMMAND_SERVER_PORT,
                (top, right, bottom, left) -> Platform.runLater(() -> applyAndSend(top, right, bottom, left, "external")),
                message -> Platform.runLater(() -> {
                    serverStatusLabel.setText(message);
                    log(message);
                })
        );
        commandServer.start();
    }

    // -------------------------------------------------------------- Logging

    private void log(String message) {
        String line = "[" + LocalDateTime.now().format(timestampFormat) + "] " + message;
        logArea.appendText(line + System.lineSeparator());
        // Auto-scroll to bottom
        logArea.selectPositionCaret(logArea.getLength());
        logArea.deselect();
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
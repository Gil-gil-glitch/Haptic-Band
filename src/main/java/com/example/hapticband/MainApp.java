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

import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.io.File;

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
    private final TextField baudField = new TextField("9600");
    private final Button connectButton = new Button("Connect");

    private File csvFile;
    private Writer csvWriter;
    private final DateTimeFormatter timestampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    @Override
    public void start(Stage stage) {
        BorderPane root = new BorderPane();
        root.setTop(buildConnectionBar());
        root.setCenter(buildCenter());
        root.setBottom(buildBottom());
        root.setPadding(new Insets(12));

        Scene scene = new Scene(root, 780, 720);
        scene.getStylesheets().add(getClass().getResource("/com/hapticglove/style.css").toExternalForm());

        stage.setTitle("Haptic Glove Monitor");
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> shutdown());
        stage.show();

        refreshPorts();
        startCommandServer();

    }


    private Node buildConnectionBar() {
        HBox bar = new HBox(8);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(0, 0, 12, 0));

        Button refreshButton = new Button("Refresh");
        refreshButton.setOnAction(e -> refreshPorts());

        baudField.setPrefWidth(70);

        connectButton.setOnAction(e -> toggleConnection());

        connectionStatusLabel.getStyleClass().add("status-disconnected");

        bar.getChildren().addAll(
                new Label("Port:"), portCombo,
                refreshButton,
                new Label("Baud:"), baudField,
                connectButton,
                connectionStatusLabel
        );
        return bar;
    }

    private Node buildCenter() {
        VBox center = new VBox(18);
        center.setAlignment(Pos.TOP_CENTER);

        // Cross layout matching the physical motor positions on the glove
        GridPane cross = new GridPane();
        cross.setAlignment(Pos.CENTER);
        cross.setHgap(24);
        cross.setVgap(12);
        cross.add(wrap(topGauge), 1, 0);
        cross.add(wrap(leftGauge), 0, 1);
        cross.add(compassLabel(), 1, 1);
        cross.add(wrap(rightGauge), 2, 1);
        cross.add(wrap(bottomGauge), 1, 2);

        TitledPane crossPane = new TitledPane("Live Motor Output", cross);
        crossPane.setCollapsible(false);

        center.getChildren().addAll(crossPane, buildManualControls(), buildPresets());
        return center;
    }

    private Node wrap(MotorGauge gauge) {
        StackPane pane = new StackPane(gauge);
        pane.setPadding(new Insets(4));
        return pane;
    }

    private Node compassLabel() {
        Label label = new Label("GLOVE");
        label.getStyleClass().add("compass-label");
        StackPane pane = new StackPane(label);
        pane.setPrefSize(70, 70);
        pane.getStyleClass().add("compass-box");
        return pane;
    }

    private Node buildManualControls() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);

        addSliderRow(grid, 0, "Top", topSlider);
        addSliderRow(grid, 1, "Right", rightSlider);
        addSliderRow(grid, 2, "Bottom", bottomSlider);
        addSliderRow(grid, 3, "Left", leftSlider);

        Button sendButton = new Button("Send Current Sliders");
        sendButton.setOnAction(e -> applyAndSend(
                (int) topSlider.getValue(), (int) rightSlider.getValue(),
                (int) bottomSlider.getValue(), (int) leftSlider.getValue(), "manual"));

        Button allOffButton = new Button("All Off");
        allOffButton.setOnAction(e -> {
            topSlider.setValue(0);
            rightSlider.setValue(0);
            bottomSlider.setValue(0);
            leftSlider.setValue(0);
            applyAndSend(0, 0, 0, 0, "manual");
        });

        HBox buttons = new HBox(8, sendButton, allOffButton);
        VBox box = new VBox(8, grid, buttons);
        TitledPane pane = new TitledPane("Manual Control", box);
        pane.setCollapsible(false);
        return pane;
    }

    private void addSliderRow(GridPane grid, int row, String name, Slider slider) {
        slider.setPrefWidth(300);
        Label valueLabel = new Label("0");
        valueLabel.setPrefWidth(30);
        slider.valueProperty().addListener((obs, oldV, newV) ->
                valueLabel.setText(String.valueOf(newV.intValue())));
        grid.add(new Label(name), 0, row);
        grid.add(slider, 1, row);
        grid.add(valueLabel, 2, row);
    }

    private Node buildPresets() {
        Button pulseTop = new Button("Pulse Top (100%)");
        pulseTop.setOnAction(e -> applyAndSend(255, 0, 0, 0, "preset"));

        Button pulseRight = new Button("Pulse Right (50%)");
        pulseRight.setOnAction(e -> applyAndSend(0, 127, 0, 0, "preset"));

        Button allHalf = new Button("All 50%");
        allHalf.setOnAction(e -> applyAndSend(127, 127, 127, 127, "preset"));

        Button allQuarter = new Button("All 25%");
        allQuarter.setOnAction(e -> applyAndSend(64, 64, 64, 64, "preset"));

        Button allOff = new Button("All Off");
        allOff.setOnAction(e -> applyAndSend(0, 0, 0, 0, "preset"));

        Button runSequence = new Button("Run Full Test Sequence");
        runSequence.setOnAction(e -> runTestSequence());

        FlowPane flow = new FlowPane(8, 8, pulseTop, pulseRight, allHalf, allQuarter, allOff, runSequence);
        TitledPane pane = new TitledPane("Presets (matches original test script)", flow);
        pane.setCollapsible(false);
        return pane;
    }

    private Node buildBottom() {
        logArea.setEditable(false);
        logArea.setPrefRowCount(8);

        csvLogCheck.setOnAction(e -> {
            if (csvLogCheck.isSelected()) {
                chooseCsvFile();
            } else {
                closeCsvWriter();
            }
        });

        serverStatusLabel.getStyleClass().add("server-status");

        VBox box = new VBox(6, new Label("Activity Log"), logArea, csvLogCheck, serverStatusLabel);
        box.setPadding(new Insets(12, 0, 0, 0));
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

    /** Updates the UI, sends over serial, and logs. This is the single path
     *  every command flows through, whether it came from this app's own
     *  controls or from an external process via CommandServer. */
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
        log("Running full test sequence...");
        int[][] steps = {
                {255, 0, 0, 0},
                {0, 127, 0, 0},
                {127, 127, 127, 127},
                {64, 64, 64, 64},
                {0, 0, 0, 0}
        };

        applyAndSend(steps[0][0], steps[0][1], steps[0][2], steps[0][3], "sequence");

        SequentialTransition seq = new SequentialTransition();
        for (int i = 1; i < steps.length; i++) {
            int[] step = steps[i];
            PauseTransition pause = new PauseTransition(Duration.seconds(1));
            pause.setOnFinished(e -> applyAndSend(step[0], step[1], step[2], step[3], "sequence"));
            seq.getChildren().add(pause);
        }
        seq.setOnFinished(e -> log("Test sequence complete."));
        seq.play();
    }

    // -------------------------------------------------------- Command server

    private void startCommandServer() {
        commandServer = new CommandServer(
                COMMAND_SERVER_PORT,
                (top, right, bottom, left) -> applyAndSend(top, right, bottom, left, "external"),
                message -> {
                    serverStatusLabel.setText(message);
                    log(message);
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

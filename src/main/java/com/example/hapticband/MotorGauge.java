package com.example.hapticband;


import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;

/** A single motor's live PWM readout:
    * name
    * fill bar
    * numeric value.
 * */

public class MotorGauge extends VBox {

    private final ProgressBar bar = new ProgressBar(0);
    private final Label valueLabel = new Label("0 (0%)");

    public MotorGauge(String motorName) {
        setAlignment(Pos.CENTER);
        setSpacing(4);
        getStyleClass().add("motor-gauge");

        Label nameLabel = new Label(motorName);
        nameLabel.getStyleClass().add("motor-name");

        bar.setPrefWidth(100);
        bar.setPrefHeight(20);
        bar.getStyleClass().add("motor-bar");

        valueLabel.getStyleClass().add("motor-value");

        getChildren().addAll(nameLabel, bar, valueLabel);
    }

    public void setPwm(int pwm) {
        int clamped = Math.max(0, Math.min(255, pwm));
        double fraction = clamped / 255.0;
        int percent = (int) Math.round(fraction * 100);

        bar.setProgress(fraction);
        valueLabel.setText(clamped + " (" + percent + "%)");

        if (clamped > 0) {
            if (!bar.getStyleClass().contains("motor-bar-active")) {
                bar.getStyleClass().add("motor-bar-active");
            }
        } else {
            bar.getStyleClass().remove("motor-bar-active");
        }
    }
}

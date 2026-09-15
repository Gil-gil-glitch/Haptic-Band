const int motorTop = 13;
const int motorRight = 14;
const int motorBottom = 26;
const int motorLeft = 27;

void setup() {
    Serial.begin(9600);

    pinMode(motorTop, OUTPUT);
    pinMode(motorRight, OUTPUT);
    pinMode(motorBottom, OUTPUT);
    pinMode(motorLeft, OUTPUT);

    analogWrite(motorTop, 0);
    analogWrite(motorRight, 0);
    analogWrite(motorBottom, 0);
    analogWrite(motorLeft, 0);
}

void loop() {
    // Read incoming motor PWM commands from JavaFX app over USB Serial
    if (Serial.available() > 0) {
    int valTop = Serial.parseInt();
    int valRight = Serial.parseInt();
    int valBottom = Serial.parseInt();
    int valLeft = Serial.parseInt();

    // Clear buffer at newline and update motor states
    if (Serial.read() == '\n') {
    analogWrite(motorTop, constrain(valTop, 0, 255));
    analogWrite(motorRight, constrain(valRight, 0, 255));
    analogWrite(motorBottom, constrain(valBottom, 0, 255));
    analogWrite(motorLeft, constrain(valLeft, 0, 255));
}
}
}
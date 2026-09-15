// This is a testing script that buzzes each motor twice

const int motorTop = 13;
const int motorRight = 14;
const int motorBottom = 26;
const int motorLeft = 27;

const int motors[] = {motorTop, motorRight, motorBottom, motorLeft};
const char* motorNames[] = {"Top (Pin 13)", "Right (Pin 14)", "Bottom (Pin 26)", "Left (Pin 27)"};

void setup() {
    Serial.begin(9600);

    for (int i = 0; i < 4; i++) {
    pinMode(motors[i], OUTPUT);
    analogWrite(motors[i], 0);
}

    Serial.println("Starting 4-Motor Test (2 buzzes per motor)...");

    for (int i = 0; i < 4; i++) {
        for (int buzz = 1; buzz <= 2; buzz++) {
        Serial.print("Testing ");
        Serial.print(motorNames[i]);
        Serial.print(" - Buzz ");
        Serial.println(buzz);

        analogWrite(motors[i], 255); // ON
        delay(400);                  // Buzz duration (0.4 sec)

        analogWrite(motors[i], 0);   // OFF
        delay(300);                  // Pause between buzzes

    }
    delay(500); // Pause before moving to the next motor
}

    Serial.println("Test complete! All motors turned OFF.");
}

void loop() {
    // Empty loop keeps all motors off permanently after setup completes
}
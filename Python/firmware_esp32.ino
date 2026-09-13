#include <Wire.h>
#include <Adafruit_Sensor.h>
#include <Adafruit_BNO055.h>

// Motor PWM Pins (ESP32)
const int motorTop = 13;
const int motorRight = 14;
const int motorBottom = 26;
const int motorLeft = 27;

// I2C Pins (ESP32)
#define SDA_PIN 21
#define SCL_PIN 22

Adafruit_BNO055 bno = Adafruit_BNO055(55, 0x28, &Wire);

unsigned long lastImuRead = 0;
const unsigned long imuInterval = 50; // Read IMU every 50ms (20Hz)

void setup() {
  Serial.begin(9600);

  // Initialize custom I2C bus on GPIO 21 and 22 for the ESP32
  Wire.begin(SDA_PIN, SCL_PIN);

  pinMode(motorTop, OUTPUT);
  pinMode(motorRight, OUTPUT);
  pinMode(motorBottom, OUTPUT);
  pinMode(motorLeft, OUTPUT);

  analogWrite(motorTop, 0);
  analogWrite(motorRight, 0);
  analogWrite(motorBottom, 0);
  analogWrite(motorLeft, 0);

  if (!bno.begin()) {
    Serial.println("ERR:BNO055_NOT_FOUND");

  } else {
    bno.setExtCrystalUse(true);

  }
}

void loop() {

  if (Serial.available() > 0) {
    int valTop = Serial.parseInt();
    int valRight = Serial.parseInt();
    int valBottom = Serial.parseInt();
    int valLeft = Serial.parseInt();

    if (Serial.read() == '\n') {
      analogWrite(motorTop, constrain(valTop, 0, 255));
      analogWrite(motorRight, constrain(valRight, 0, 255));
      analogWrite(motorBottom, constrain(valBottom, 0, 255));
      analogWrite(motorLeft, constrain(valLeft, 0, 255));

    }

  }

  if (millis() - lastImuRead >= imuInterval) {
    lastImuRead = millis();
    sensors_event_t orientationData;
    bno.getEvent(&orientationData, Adafruit_BNO055::VECTOR_EULER);

    // Format: IMU:<Yaw>,<Pitch>,<Roll>
    Serial.print("IMU:");
    Serial.print(orientationData.orientation.x); // Yaw (0 to 360)
    Serial.print(",");
    Serial.print(orientationData.orientation.y); // Pitch (-180 to 180)
    Serial.print(",");
    Serial.println(orientationData.orientation.z); // Roll (-90 to 90)
  }
}
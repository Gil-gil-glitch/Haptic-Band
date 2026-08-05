// PWM pins for the 4 motors
const int motorTop = 3;
const int motorRight = 5;
const int motorBottom = 6;
const int motorLeft = 9;

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

  if (Serial.available() > 0) {

    int valTop = Serial.parseInt();
    int valRight = Serial.parseInt();
    int valBottom = Serial.parseInt();
    int valLeft = Serial.parseInt();
    
    // Read the newline character at the end to clear the buffer
    if (Serial.read() == '\n') {
      // Constrain values to 0-255 just to be safe
      valTop = constrain(valTop, 0, 255);
      valRight = constrain(valRight, 0, 255);
      valBottom = constrain(valBottom, 0, 255);
      valLeft = constrain(valLeft, 0, 255);
      
      analogWrite(motorTop, valTop);
      analogWrite(motorRight, valRight);
      analogWrite(motorBottom, valBottom);
      analogWrite(motorLeft, valLeft);
    }
  }
}
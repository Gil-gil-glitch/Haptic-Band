import serial
import time

arduino = serial.Serial(port='COM4', baudrate=9600, timeout=1)

time.sleep(2) 

def set_motors(top, right, bottom, left):
    """
    Sends PWM values (0-255) to the 4 vibration motors.
    """

    command = f"{top},{right},{bottom},{left}\n" # Format that Arduino expects
    
    arduino.write(command.encode('utf-8'))
    print(f"Sent: {command.strip()}")

try:
    print("Testing Haptic Feedback...")
    
    # Pulse the top motor at 100% (255)
    set_motors(255, 0, 0, 0)
    time.sleep(1)
    
    # Pulse the right motor at 50% (127)
    set_motors(0, 127, 0, 0)
    time.sleep(1)

    # Pulse all motors at 50% (127)
    set_motors(127, 127, 127, 127)
    time.sleep(1)
    
    # Turn all motors on at 25% (64)
    set_motors(64, 64, 64, 64)
    time.sleep(1)
    
    # Turn all motors off
    set_motors(0, 0, 0, 0)
    print("Test complete.")

except KeyboardInterrupt:

    set_motors(0, 0, 0, 0)
    arduino.close()
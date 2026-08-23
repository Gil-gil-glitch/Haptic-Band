import time
from haptic_client import HapticClient

# Using Context Manager (handles connect and auto-disconnect/stop on exit)
with HapticClient(host='localhost', port=5050) as client:
    print("Testing Haptic Feedback via Python Client API...")

    # Pulse Top motor at 100% PWM for 1 second
    client.pulse_direction('top', pwm=255, duration=1.0)

    # Pulse Right motor at 50% PWM for 1 second
    client.pulse_direction('right', pwm=127, duration=1.0)

    # Pulse All motors at 50% (127)[cite: 5]
    client.send_raw(127, 127, 127, 127)
    time.sleep(1.0)

    # Set all motors to 25% (64)[cite: 5]
    client.send_raw(64, 64, 64, 64)
    time.sleep(1.0)

    print("Test complete.")
"""Demonstrate the TCP client after starting the GUI and connecting its serial port."""

import argparse
import time


def main(args=None):
    """Run a sequence through the GUI's command server, then stop all motors."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="localhost", help="GUI server host (default: localhost)")
    parser.add_argument("--port", type=int, default=5050, help="GUI server port (default: 5050)")
    options = parser.parse_args(args)

    from haptic_band_ros.haptic_client import HapticClient

    with HapticClient(host=options.host, port=options.port) as client:
        print("Testing Haptic Feedback via Python Client API...")
        client.pulse_direction("top", pwm=255, duration_s=1.0)
        client.pulse_direction("right", pwm=127, duration_s=1.0)
        client.send_raw(127, 127, 127, 127)
        time.sleep(1.0)
        client.send_raw(64, 64, 64, 64)
        time.sleep(1.0)
        print("Test complete.")


if __name__ == "__main__":
    main()

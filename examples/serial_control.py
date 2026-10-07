"""Manually exercise all four motors through an explicitly selected serial port."""

import argparse
import time

import serial


def set_motors(connection, top, right, bottom, left):
    """Write one top/right/bottom/left PWM target to the open connection."""
    command = f"{top},{right},{bottom},{left}\n"
    connection.write(command.encode("utf-8"))
    print(f"Sent: {command.strip()}")


def main(args=None):
    """Run the original demonstration and stop the motors before closing."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", required=True, help="Serial port, e.g. /dev/ttyUSB0 or COM4")
    parser.add_argument(
        "--baud", type=int, default=9600, help="Firmware baud rate (default: 9600)"
    )
    options = parser.parse_args(args)

    with serial.Serial(port=options.port, baudrate=options.baud, timeout=1) as connection:
        try:
            time.sleep(2)  # Allow the board to reset after opening the port.
            print("Testing Haptic Feedback...")
            for target in ((255, 0, 0, 0), (0, 127, 0, 0), (127, 127, 127, 127), (64, 64, 64, 64)):
                set_motors(connection, *target)
                time.sleep(1)
            print("Test complete.")
        except KeyboardInterrupt:
            pass
        finally:
            set_motors(connection, 0, 0, 0, 0)


if __name__ == "__main__":
    main()

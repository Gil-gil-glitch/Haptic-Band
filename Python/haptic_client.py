import socket
import time
from typing import Optional

class HapticClient:
    """
    Python client for communicating with the Haptic Wristband JavaFX Command Server over TCP socket.
    This does not talk to the Arduino directly on COM4, but it communicates to the JavaFX Command
    Server over its address.
    """
    def __init__(self, host: str = 'localhost', port: int = 5050, timeout: float = 2.0):
        self.host = host
        self.port = port
        self.timeout = timeout
        self.socket: Optional[socket.socket] = None

    def connect(self) -> bool:
        """
        Establishes a connection to the JavaFX command server.
        """
        try:
            self.socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            self.socket.settimeout(self.timeout)
            self.socket.connect((self.host, self.port))
            print(f"[HapticClient] Connected to server at {self.host}:{self.port}")
            return True

        except (socket.error, ConnectionRefusedError) as e:
            print(f"[HapticClient] Connection failed: {e}")
            self.socket = None
            return False

    def send_raw(self, top: int, right: int, bottom: int, left: int) -> bool:
        """
        Sends raw PWM values (0-255) for all four motors.
        Values are automatically clamped to the valid 0-255 range.
        """

        if not self.socket:
            print("[HapticClient] Cannot send: Not connected to server.")
            return False

        # Values clamped between 0 and 255
        top = max(0, min(255, int(top)))
        right = max(0, min(255, int(right)))
        bottom = max(0, min(255, int(bottom)))
        left = max(0, min(255, int(left)))

        # Format string expected by CommandServer / Arduino
        command = f"{top},{right},{bottom},{left}\n"

        try:
            self.socket.sendall(command.encode('utf-8'))
            return True
        except socket.error as e:
            print(f"[HapticClient] Send failed: {e}")
            self.disconnect()
            return False

    # Directional Helper Methods

    def stop_all(self):
        """
        Turns off all motors immediately.
        """
        self.send_raw(0, 0, 0, 0)

    def pulse_direction(self, direction: str, pwm: int = 255, duration: float = 0.5):
        """
        Pulses a specific motor ('top', 'right', 'bottom', 'left') at a given PWM for a duration in seconds.
        """
        direction = direction.lower()
        t = pwm if direction == 'top' else 0
        r = pwm if direction == 'right' else 0
        b = pwm if direction == 'bottom' else 0
        l = pwm if direction == 'left' else 0

        self.send_raw(t, r, b, l)
        time.sleep(duration)
        self.stop_all()

    def disconnect(self):
        """
        Safely stops all motors and closes the socket connection.
        """
        if self.socket:
            try:
                self.stop_all()
                self.socket.close()

            except socket.error:
                pass

            finally:
                self.socket = None
                print("[HapticClient] Disconnected from server.")

    def __enter__(self):
        self.connect()
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        self.disconnect()
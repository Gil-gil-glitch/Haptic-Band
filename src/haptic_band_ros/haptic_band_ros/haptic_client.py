"""Client library for controlling the wristband via the app's CommandServer.

This does not open the COM port; it only talks to the CommandServer socket.

Example:
    with HapticClient() as h:
        h.pulse_direction("top", 200, 0.5)
"""
import socket
import threading
import time


def _clamp(v: int) -> int:
    return max(0, min(255, int(v)))


class HapticClient:
    def __init__(self, host="localhost", port=5050):
        self.host = host
        self.port = port
        self._sock = None
        self._lock = threading.Lock()

    def connect(self):
        if self.is_connected():
            return
        self._sock = socket.create_connection((self.host, self.port))

    def is_connected(self) -> bool:
        return self._sock is not None

    def send_raw(self, top, right, bottom, left):
        """Send raw PWM values (0-255), clamped, for all four motors."""
        with self._lock:
            if not self.is_connected():
                raise ConnectionError("HapticClient is not connected to server.")
            command = f"{_clamp(top)},{_clamp(right)},{_clamp(bottom)},{_clamp(left)}\n"
            self._sock.sendall(command.encode("utf-8"))

    def pulse_direction(self, direction: str, pwm: int, duration_s: float):
        """Pulse one motor ('top', 'right', 'bottom', 'left') then stop all.

        Note: duration is in SECONDS here (Java version used milliseconds).
        """
        d = direction.lower()
        self.send_raw(pwm if d == "top" else 0,
                      pwm if d == "right" else 0,
                      pwm if d == "bottom" else 0,
                      pwm if d == "left" else 0)
        time.sleep(duration_s)
        self.stop_all()

    def stop_all(self):
        self.send_raw(0, 0, 0, 0)

    def close(self):
        try:
            if self.is_connected():
                self.stop_all()
                self._sock.close()
        except OSError:
            pass
        finally:
            self._sock = None

    def __enter__(self):
        self.connect()
        return self

    def __exit__(self, *exc):
        self.close()
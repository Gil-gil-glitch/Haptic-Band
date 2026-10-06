"""Owns the connection to the Arduino.

Only one process can hold a COM port open at a time, so the GUI app is the
single owner of the serial link. Other control code talks to CommandServer
over a local socket instead (see haptic_band_ros.haptic_client).

Sends the "top,right,bottom,left\\n" line the Arduino sketch expects.
"""
import threading
import time

import serial
from serial.tools import list_ports


def _clamp(v: int) -> int:
    return max(0, min(255, int(v)))


class SerialManager:
    def __init__(self):
        self._port = None
        self._lock = threading.Lock()

    @staticmethod
    def list_ports() -> list:
        """System names of available serial ports, e.g. ['COM3', 'COM4']."""
        return [p.device for p in list_ports.comports()]

    def connect(self, port_name: str, baud_rate: int) -> bool:
        try:
            self._port = serial.Serial(
                port=port_name,
                baudrate=baud_rate,
                bytesize=serial.EIGHTBITS,
                parity=serial.PARITY_NONE,
                stopbits=serial.STOPBITS_ONE,
                write_timeout=1.0,
            )
        except (serial.SerialException, ValueError, OSError):
            self._port = None
            return False
        # Give the Arduino time to reset after the serial connection opens.
        time.sleep(2)
        return True

    def is_connected(self) -> bool:
        return self._port is not None and self._port.is_open

    def send_motor_values(self, top, right, bottom, left) -> None:
        with self._lock:
            if not self.is_connected():
                raise RuntimeError("Serial port is not open")
            command = f"{_clamp(top)},{_clamp(right)},{_clamp(bottom)},{_clamp(left)}\n"
            self._port.write(command.encode("utf-8"))
            self._port.flush()

    def disconnect(self) -> None:
        try:
            if self._port is not None and self._port.is_open:
                self._port.close()
        except Exception:
            pass
        self._port = None
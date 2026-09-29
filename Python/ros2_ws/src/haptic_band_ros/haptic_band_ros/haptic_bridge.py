#!/usr/bin/env python3
"""ROS 2 bridge: topic -> haptic wristband.

Subscribes to std_msgs/Int32MultiArray with data = [top, right, bottom, left]
(each 0-255 PWM) and writes "top,right,bottom,left\\n" to the ESP32, exactly
the line the existing sketch already expects. No sketch changes needed.

Output modes (parameter `output`):
  serial      - this node opens the serial port itself (default, headless).
  gui_socket  - forward to the Python GUI's CommandServer (localhost:5050);
                the GUI owns the serial port and shows live gauges.
Only one process can own the serial port, so use one mode or the other.
"""
import socket
import time

import rclpy
from rclpy.node import Node
from rcl_interfaces.msg import ParameterDescriptor
from std_msgs.msg import Int32MultiArray

import serial
from serial.tools import list_ports

# USB vendor IDs commonly found on ESP32/Arduino boards (CH340, CP210x, FTDI,
# Espressif native USB, Arduino).
PREFERRED_VIDS = {0x1A86, 0x10C4, 0x0403, 0x303A, 0x2341}


def clamp(v):
    return max(0, min(255, int(v)))


def find_port():
    ports = list(list_ports.comports())
    for p in ports:
        if p.vid in PREFERRED_VIDS:
            return p.device
    return ports[0].device if ports else None


class SerialSink:
    def __init__(self, port, baud, reset_delay_s):
        self.port_name, self.baud, self.reset_delay_s = port, baud, reset_delay_s
        self._ser = None

    @property
    def connected(self):
        return self._ser is not None and self._ser.is_open

    def describe(self):
        return f"serial {self.port_name or 'auto'} @ {self.baud}"

    def connect(self):
        name = find_port() if self.port_name in ("", "auto") else self.port_name
        if not name:
            raise OSError("no serial ports found")
        self._ser = serial.Serial(name, self.baud, write_timeout=1.0)
        # Opening the port resets most boards (DTR); let the sketch boot.
        time.sleep(self.reset_delay_s)
        self._ser.reset_input_buffer()
        return name

    def send(self, data: bytes):
        self._ser.write(data)
        self._ser.flush()

    def close(self):
        try:
            if self._ser is not None:
                self._ser.close()
        except Exception:
            pass
        self._ser = None


class SocketSink:
    def __init__(self, host, port):
        self.host, self.port = host, port
        self._sock = None

    @property
    def connected(self):
        return self._sock is not None

    def describe(self):
        return f"GUI socket {self.host}:{self.port}"

    def connect(self):
        self._sock = socket.create_connection((self.host, self.port), timeout=1.0)
        return f"{self.host}:{self.port}"

    def send(self, data: bytes):
        self._sock.sendall(data)

    def close(self):
        try:
            if self._sock is not None:
                self._sock.close()
        except OSError:
            pass
        self._sock = None


class HapticBridge(Node):
    def __init__(self):
        super().__init__("haptic_bridge")
        dyn = ParameterDescriptor(dynamic_typing=True)
        self.declare_parameter("topic", "haptic/motors")
        self.declare_parameter("output", "serial")
        self.declare_parameter("port", "auto")
        self.declare_parameter("baud", 9600, dyn)
        self.declare_parameter("host", "127.0.0.1")
        self.declare_parameter("tcp_port", 5050, dyn)
        self.declare_parameter("reset_delay_s", 2.0, dyn)
        self.declare_parameter("timeout_s", 0.0, dyn)

        p = lambda n: self.get_parameter(n).value
        output = str(p("output"))
        if output == "gui_socket":
            self.sink = SocketSink(str(p("host")), int(p("tcp_port")))
        elif output == "serial":
            self.sink = SerialSink(str(p("port")), int(p("baud")), float(p("reset_delay_s")))
        else:
            raise ValueError(f"output must be 'serial' or 'gui_socket', got '{output}'")
        self.timeout_s = float(p("timeout_s"))

        self._last_cmd = time.monotonic()
        self._active = False

        topic = str(p("topic"))
        self.create_subscription(Int32MultiArray, topic, self.on_msg, 10)
        self.create_timer(1.0, self.on_tick)  # reconnect + watchdog
        self.get_logger().info(
            f"Listening on '{topic}' [top,right,bottom,left] -> {self.sink.describe()}")
        self.try_connect()

    # ------------------------------------------------------------ link
    def try_connect(self):
        try:
            name = self.sink.connect()
        except (OSError, serial.SerialException) as e:
            self.sink.close()
            self.get_logger().warn(
                f"Not connected ({e}); retrying...", throttle_duration_sec=10.0)
            return
        self.get_logger().info(f"Connected: {name}")
        self.send_values([0, 0, 0, 0])

    def send_values(self, vals):
        line = "{},{},{},{}\n".format(*vals).encode("utf-8")
        try:
            self.sink.send(line)
            return True
        except (OSError, serial.SerialException) as e:
            self.get_logger().error(f"Send failed ({e}); will reconnect.")
            self.sink.close()
            return False

    # -------------------------------------------------------- callbacks
    def on_msg(self, msg):
        data = list(msg.data)
        if len(data) != 4:
            self.get_logger().warn(
                f"Expected 4 values [top,right,bottom,left], got {len(data)}; ignored.",
                throttle_duration_sec=5.0)
            return
        vals = [clamp(v) for v in data]
        self._last_cmd = time.monotonic()
        self._active = any(vals)
        if not self.sink.connected:
            self.get_logger().warn("Command dropped: not connected.", throttle_duration_sec=5.0)
            return
        if self.send_values(vals):
            self.get_logger().debug(f"Sent {vals}")

    def on_tick(self):
        if not self.sink.connected:
            self.try_connect()
            return
        if (self.timeout_s > 0 and self._active
                and time.monotonic() - self._last_cmd > self.timeout_s):
            self.get_logger().info("Command timeout: stopping motors.")
            self._active = False
            self.send_values([0, 0, 0, 0])

    def close(self):
        if self.sink.connected:
            self.send_values([0, 0, 0, 0])
        self.sink.close()


def main(args=None):
    rclpy.init(args=args)
    node = HapticBridge()
    try:
        rclpy.spin(node)
    except KeyboardInterrupt:
        pass
    finally:
        node.close()
        node.destroy_node()
        if rclpy.ok():
            rclpy.shutdown()


if __name__ == "__main__":
    main()
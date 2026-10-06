#!/usr/bin/env python3
"""Forward ROS motor targets to serial or the optional GUI's TCP server.

Commands are std_msgs/Int32MultiArray [top, right, bottom, left], each 0-255.
The output worker owns all I/O and sends the existing newline-delimited CSV
protocol. ROS callbacks only replace the latest pending target.
"""

import math
import socket

from rcl_interfaces.msg import ParameterDescriptor, SetParametersResult
import rclpy
from rclpy.executors import ExternalShutdownException
from rclpy.node import Node
from rclpy.qos import DurabilityPolicy, HistoryPolicy, QoSProfile, ReliabilityPolicy
import serial
from serial.tools import list_ports
from std_msgs.msg import Int32MultiArray

from .output_worker import OutputWorker


# Common ESP32/Arduino USB adapters: CH340, CP210x, FTDI and native USB.
PREFERRED_VIDS = {0x1A86, 0x10C4, 0x0403, 0x303A, 0x2341}


def clamp(value):
    """Clamp one PWM value to the firmware's range."""
    return max(0, min(255, int(value)))


def find_port():
    """Find a likely board, retaining the original automatic-port behavior."""
    ports = list(list_ports.comports())
    for port in ports:
        if port.vid in PREFERRED_VIDS:
            return port.device
    return ports[0].device if ports else None


class SerialSink:
    """Serial transport accessed exclusively by the output worker."""

    def __init__(self, port, baud, reset_delay_s, io_timeout_s):
        self.port_name = port
        self.baud = baud
        self.reset_delay_s = reset_delay_s
        self.io_timeout_s = io_timeout_s
        self._ser = None

    @property
    def connected(self):
        """Report port-open state, not firmware acknowledgement."""
        return self._ser is not None and self._ser.is_open

    def describe(self):
        """Describe the configured output."""
        return f'serial {self.port_name or "auto"} @ {self.baud}'

    def connect(self, stop):
        """Open the port and wait interruptibly for the board to reset."""
        name = find_port() if self.port_name in ('', 'auto') else self.port_name
        if not name:
            raise OSError('no serial ports found')
        self._ser = serial.Serial(name, self.baud, write_timeout=self.io_timeout_s)
        if stop.wait(self.reset_delay_s):
            raise ConnectionAbortedError('shutdown during board reset')
        self._ser.reset_input_buffer()
        return name

    def send(self, data):
        """Write with a timeout; avoid the unbounded POSIX flush/tcdrain call."""
        if self._ser.write(data) != len(data):
            raise OSError('incomplete serial write')

    def close(self):
        """Release the serial port."""
        try:
            if self._ser is not None:
                self._ser.close()
        except OSError:
            pass
        self._ser = None


class SocketSink:
    """Legacy GUI transport; TCP delivery does not acknowledge motor output."""

    def __init__(self, host, port, io_timeout_s):
        self.host = host
        self.port = port
        self.io_timeout_s = io_timeout_s
        self._sock = None

    @property
    def connected(self):
        """Report whether a socket has been opened."""
        return self._sock is not None

    def describe(self):
        """Describe the configured output."""
        return f'GUI socket {self.host}:{self.port}'

    def connect(self, stop):
        """Connect with a timeout and disable TCP small-packet buffering."""
        self._sock = socket.create_connection(
            (self.host, self.port), timeout=self.io_timeout_s)
        self._sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        return f'{self.host}:{self.port}'

    def send(self, data):
        """Send a complete line using the socket's write timeout."""
        self._sock.sendall(data)

    def close(self):
        """Release the socket."""
        try:
            if self._sock is not None:
                self._sock.close()
        except OSError:
            pass
        self._sock = None


def validate_settings(settings):
    """Reject invalid settings at startup and in parameter callbacks."""
    for name in ('timeout_s', 'reset_delay_s', 'io_timeout_s', 'max_rate_hz'):
        value = settings.get(name)
        if value is None:
            continue
        minimum_inclusive = name in ('timeout_s', 'reset_delay_s', 'max_rate_hz')
        if not isinstance(value, float) or not math.isfinite(value) or (
            value < 0 if minimum_inclusive else value <= 0
        ):
            bound = '>= 0' if minimum_inclusive else '> 0'
            raise ValueError(f'{name} must be a finite floating-point value {bound}')
    if 'baud' in settings and settings['baud'] <= 0:
        raise ValueError('baud must be positive')
    if 'tcp_port' in settings and not 1 <= settings['tcp_port'] <= 65535:
        raise ValueError('tcp_port must be between 1 and 65535')
    if 'output' in settings and settings['output'] not in ('serial', 'gui_socket'):
        raise ValueError("output must be 'serial' or 'gui_socket'")
    if 'reliability' in settings and settings['reliability'] not in (
        'best_effort', 'reliable'
    ):
        raise ValueError("reliability must be 'best_effort' or 'reliable'")


class HapticBridge(Node):
    """Subscribe to motor setpoints and hand them to a dedicated I/O worker."""

    def __init__(self, **kwargs):
        super().__init__('haptic_bridge', **kwargs)
        self.worker = None
        defaults = {
            'topic': 'haptic/motors',
            'output': 'serial',
            'port': 'auto',
            'baud': 9600,
            'host': '127.0.0.1',
            'tcp_port': 5050,
            'reset_delay_s': 2.0,
            'io_timeout_s': 0.1,
            'max_rate_hz': 0.0,
            'reliability': 'best_effort',
            'timeout_s': 0.5,
        }
        descriptions = {
            'timeout_s': 'Command timeout in monotonic seconds; 0 disables automatic stop.',
            'max_rate_hz': 'Optional software rate limit; 0 disables it. Restart to change.',
        }
        try:
            for name, default in defaults.items():
                description = descriptions.get(name, 'Startup setting; restart to change.')
                self.declare_parameter(name, default, ParameterDescriptor(
                    read_only=name != 'timeout_s', description=description))
            settings = {name: self.get_parameter(name).value for name in defaults}
            validate_settings(settings)

            max_rate = settings['max_rate_hz']
            if settings['output'] == 'serial':
                self.sink = SerialSink(
                    settings['port'], settings['baud'], settings['reset_delay_s'],
                    settings['io_timeout_s'])
            else:
                self.sink = SocketSink(
                    settings['host'], settings['tcp_port'], settings['io_timeout_s'])

            self.worker = OutputWorker(
                self.sink, settings['timeout_s'], max_rate, log=self._log_output,
                baud=settings['baud'] if settings['output'] == 'serial' else None)
            self.add_on_set_parameters_callback(self._validate_parameters)
            self.add_post_set_parameters_callback(self._apply_parameters)

            qos = QoSProfile(
                history=HistoryPolicy.KEEP_LAST,
                depth=1,
                reliability=(ReliabilityPolicy.BEST_EFFORT
                             if settings['reliability'] == 'best_effort'
                             else ReliabilityPolicy.RELIABLE),
                durability=DurabilityPolicy.VOLATILE,
            )
            self.create_subscription(Int32MultiArray, settings['topic'], self.on_msg, qos)
            rate_description = f'{max_rate:g}Hz' if max_rate > 0 else 'disabled'
            self.get_logger().info(
                f"Listening on '{settings['topic']}' [top,right,bottom,left] -> "
                f'{self.sink.describe()}; '
                f"QoS={settings['reliability']}, depth=1; "
                f"timeout={settings['timeout_s']}s; software rate limit={rate_description}")
            self.worker.start()
        except Exception:
            self.close()
            self.destroy_node()
            raise

    def _log_output(self, level, message):
        getattr(self.get_logger(), level)(message)

    def _validate_parameters(self, parameters):
        try:
            validate_settings({p.name: p.value for p in parameters})
        except ValueError as exc:
            return SetParametersResult(successful=False, reason=str(exc))
        return SetParametersResult(successful=True)

    def _apply_parameters(self, parameters):
        for parameter in parameters:
            if parameter.name == 'timeout_s':
                self.worker.set_timeout(parameter.value)

    def on_msg(self, msg):
        """Validate and replace a target without performing transport I/O."""
        if len(msg.data) != 4:
            self.get_logger().warning(
                f'Expected 4 values [top,right,bottom,left], got {len(msg.data)}; ignored.',
                throttle_duration_sec=5.0)
            return
        if not self.worker.submit(tuple(clamp(value) for value in msg.data)):
            self.get_logger().warning(
                'Command dropped: output not ready.', throttle_duration_sec=5.0)

    def close(self):
        """Stop the transport owner before destroying the ROS node."""
        if self.worker is not None:
            self.worker.close()


def main(args=None):
    """Run until interrupted, then attempt to stop all motors."""
    rclpy.init(args=args)
    node = None
    try:
        node = HapticBridge()
        rclpy.spin(node)
    except (KeyboardInterrupt, ExternalShutdownException):
        pass
    finally:
        if node is not None:
            node.close()
            node.destroy_node()
        if rclpy.ok():
            rclpy.shutdown()


if __name__ == '__main__':
    main()

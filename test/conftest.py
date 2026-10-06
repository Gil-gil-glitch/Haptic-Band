"""Hardware-free fixtures for transport scheduling and ROS integration tests."""

import queue
import threading
import time

import pytest


class RecordingSink:
    """Simulate a slow/resetting/broken output without touching a serial port."""

    def __init__(self):
        self.connected = False
        self.lines = queue.Queue()
        self.connect_gate = threading.Event()
        self.connect_gate.set()
        self.write_gate = threading.Event()
        self.write_gate.set()
        self.write_started = threading.Event()
        self.connect_started = threading.Event()
        self.fail_next = False
        self.owners = set()

    def describe(self):
        return 'test output (no hardware)'

    def connect(self, stop):
        self.owners.add(threading.get_ident())
        self.connect_started.set()
        while not self.connect_gate.wait(0.005):
            if stop.is_set():
                raise ConnectionAbortedError('shutdown')
        self.connected = True
        return 'test output'

    def send(self, data):
        self.owners.add(threading.get_ident())
        if data != b'0,0,0,0\n':
            self.write_started.set()
            if not self.write_gate.wait(2.0):
                raise TimeoutError('simulated slow write timed out')
        if self.fail_next:
            self.fail_next = False
            raise OSError('simulated disconnect')
        self.lines.put((data, time.monotonic()))

    def close(self):
        self.owners.add(threading.get_ident())
        self.connected = False

    def read(self, timeout=1.0):
        return self.lines.get(timeout=timeout)


@pytest.fixture
def sink():
    return RecordingSink()


@pytest.fixture
def wait_until():
    def wait(predicate, timeout=1.0):
        deadline = time.monotonic() + timeout
        while not predicate():
            assert time.monotonic() < deadline, 'condition did not become true'
            time.sleep(0.002)
    return wait


@pytest.fixture
def make_worker(sink):
    from haptic_band_ros.output_worker import OutputWorker
    workers = []

    def make(timeout_s=0.5, max_rate_hz=0.0, baud=None):
        worker = OutputWorker(sink, timeout_s, max_rate_hz, reconnect_s=0.01, baud=baud)
        workers.append(worker)
        worker.start()
        return worker

    yield make
    sink.write_gate.set()
    sink.connect_gate.set()
    for worker in workers:
        worker.close()


@pytest.fixture
def ros_context(monkeypatch):
    from rclpy.context import Context
    monkeypatch.setenv('ROS_AUTOMATIC_DISCOVERY_RANGE', 'LOCALHOST')
    context = Context()
    context.init(args=[], domain_id=183)
    yield context
    context.shutdown()


@pytest.fixture
def make_bridge(monkeypatch, ros_context, sink):
    from haptic_band_ros import haptic_bridge
    from rclpy.parameter import Parameter
    monkeypatch.setattr(haptic_bridge, 'SerialSink', lambda *args: sink)
    nodes = []

    def make(**parameters):
        node = haptic_bridge.HapticBridge(
            context=ros_context, namespace='/haptic_test',
            parameter_overrides=[Parameter(k, value=v) for k, v in parameters.items()])
        nodes.append(node)
        return node

    yield make
    sink.write_gate.set()
    sink.connect_gate.set()
    for node in nodes:
        node.close()
        node.destroy_node()

"""Verify ROS parameters, QoS, paused clocks, and unchanged command format."""

import math
import queue
import time

import pytest
from rclpy.executors import SingleThreadedExecutor
from rclpy.node import Node
from rclpy.parameter import Parameter
from rclpy.qos import DurabilityPolicy, QoSProfile, ReliabilityPolicy
from std_msgs.msg import Int32MultiArray


ZERO = b'0,0,0,0\n'


def test_timeout_parameter_changes_effective_behavior(make_bridge, sink, wait_until):
    node = make_bridge(timeout_s=0.0)
    wait_until(lambda: node.worker.ready)
    sink.read()
    node.on_msg(Int32MultiArray(data=[80, 0, 0, 0]))
    sink.read()
    time.sleep(0.08)
    result = node.set_parameters([Parameter('timeout_s', value=0.04)])[0]
    assert result.successful
    assert node.worker.timeout_s == node.get_parameter('timeout_s').value == 0.04
    assert sink.read(timeout=0.2)[0] == ZERO


@pytest.mark.parametrize('value', [-1.0, math.nan, math.inf, -math.inf])
def test_invalid_timeout_is_rejected_atomically(make_bridge, value):
    node = make_bridge()
    result = node.set_parameters([Parameter('timeout_s', value=value)])[0]
    assert not result.successful
    assert node.get_parameter('timeout_s').value == node.worker.timeout_s == 0.5


def test_read_only_setting_does_not_partially_apply_timeout(make_bridge):
    node = make_bridge()
    result = node.set_parameters_atomically([
        Parameter('timeout_s', value=0.1), Parameter('baud', value=115200)])
    assert not result.successful
    assert node.get_parameter('timeout_s').value == node.worker.timeout_s == 0.5
    assert node.get_parameter('baud').value == 9600


def test_software_rate_limit_is_disabled_by_default_and_read_only(make_bridge):
    node = make_bridge()
    assert node.get_parameter('max_rate_hz').value == 0.0
    result = node.set_parameters([Parameter('max_rate_hz', value=60.0)])[0]
    assert not result.successful
    assert node.get_parameter('max_rate_hz').value == 0.0


@pytest.mark.parametrize('settings', [
    {'timeout_s': -0.1}, {'reset_delay_s': math.inf}, {'io_timeout_s': 0.0},
    {'max_rate_hz': -1.0}, {'baud': 0}, {'tcp_port': 65536},
    {'reliability': 'anything'}, {'output': 'anything'},
])
def test_invalid_startup_configuration_is_rejected(make_bridge, sink, settings):
    with pytest.raises(ValueError):
        make_bridge(**settings)
    assert not sink.connect_started.is_set()


def test_paused_ros_clock_does_not_pause_watchdog(make_bridge, sink, wait_until):
    node = make_bridge(use_sim_time=True, timeout_s=0.06)
    wait_until(lambda: node.worker.ready)
    sink.read()
    node.on_msg(Int32MultiArray(data=[80, 0, 0, 0]))
    assert sink.read()[0] == b'80,0,0,0\n'
    assert node.get_clock().now().nanoseconds == 0
    assert sink.read(timeout=0.4)[0] == ZERO


def test_invalid_message_does_not_refresh_watchdog(make_bridge, sink, wait_until):
    node = make_bridge(timeout_s=0.06)
    wait_until(lambda: node.worker.ready)
    sink.read()
    node.on_msg(Int32MultiArray(data=[80, 0, 0, 0]))
    sink.read()
    for _ in range(4):
        node.on_msg(Int32MultiArray(data=[80, 0, 0]))
        time.sleep(0.02)
    assert sink.read(timeout=0.2)[0] == ZERO


@pytest.mark.parametrize('publisher_reliability, subscriber_reliability', [
    (ReliabilityPolicy.BEST_EFFORT, 'best_effort'),
    (ReliabilityPolicy.RELIABLE, 'best_effort'),
    (ReliabilityPolicy.RELIABLE, 'reliable'),
])
def test_ros_publication_reaches_output_with_expected_qos(
    make_bridge, ros_context, sink, wait_until,
    publisher_reliability, subscriber_reliability
):
    node = make_bridge(reliability=subscriber_reliability)
    wait_until(lambda: node.worker.ready)
    assert sink.read()[0] == ZERO
    subscription = next(s for s in node.subscriptions if s.topic_name.endswith('/haptic/motors'))
    assert subscription.qos_profile.depth == 1
    assert subscription.qos_profile.durability == DurabilityPolicy.VOLATILE
    expected = (ReliabilityPolicy.BEST_EFFORT if subscriber_reliability == 'best_effort'
                else ReliabilityPolicy.RELIABLE)
    assert subscription.qos_profile.reliability == expected
    publisher_node = Node('haptic_test_publisher', context=ros_context)
    executor = SingleThreadedExecutor(context=ros_context)
    executor.add_node(node)
    executor.add_node(publisher_node)
    try:
        publisher = publisher_node.create_publisher(
            Int32MultiArray, '/haptic_test/haptic/motors',
            QoSProfile(depth=1, reliability=publisher_reliability))
        deadline = time.monotonic() + 5.0
        while publisher.get_subscription_count() == 0:
            assert time.monotonic() < deadline, 'ROS endpoints did not discover each other'
            executor.spin_once(timeout_sec=0.01)
        publisher.publish(Int32MultiArray(data=[-5, 500, 1, 2]))
        deadline = time.monotonic() + 2.0
        while sink.lines.empty():
            assert time.monotonic() < deadline, 'ROS command did not reach output'
            executor.spin_once(timeout_sec=0.01)
        assert sink.read()[0] == b'0,255,1,2\n'
        publisher.publish(Int32MultiArray(data=[0, 0, 0, 0]))
        deadline = time.monotonic() + 0.4
        while sink.lines.empty():
            assert time.monotonic() < deadline
            executor.spin_once(timeout_sec=0.01)
        assert sink.read()[0] == ZERO
        with pytest.raises(queue.Empty):
            sink.read(timeout=0.03)
    finally:
        executor.shutdown()
        publisher_node.destroy_node()

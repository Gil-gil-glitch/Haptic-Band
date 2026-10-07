"""Exercise output timing, congestion, reconnection, and orderly shutdown."""

import queue
import threading
import time
from types import SimpleNamespace

import pytest


ZERO = b"0,0,0,0\n"


def test_timeout_runs_without_ros_and_bypasses_rate_limit(make_worker, sink, wait_until):
    worker = make_worker(timeout_s=0.06, max_rate_hz=1.0)
    wait_until(lambda: worker.ready)
    assert sink.read()[0] == ZERO
    assert worker.submit((80, 0, 0, 0))
    command, sent_at = sink.read()
    assert command == b"80,0,0,0\n"
    command, stopped_at = sink.read(timeout=0.4)
    assert command == ZERO
    assert 0.04 <= stopped_at - sent_at < 0.4


def test_slow_io_does_not_block_producer_and_only_latest_target_survives(
    make_worker, sink, wait_until
):
    worker = make_worker(timeout_s=2.0)
    wait_until(lambda: worker.ready)
    sink.read()
    sink.write_gate.clear()
    worker.submit((1, 0, 0, 0))
    assert sink.write_started.wait(1.0)
    finished = threading.Event()

    def produce():
        for value in range(2, 101):
            assert worker.submit((value, 0, 0, 0))
        finished.set()

    producer = threading.Thread(target=produce)
    producer.start()
    try:
        assert finished.wait(0.3), "producer blocked behind transport I/O"
    finally:
        sink.write_gate.set()
        producer.join(timeout=1.0)
    assert sink.read()[0] == b"1,0,0,0\n"
    assert sink.read()[0] == b"100,0,0,0\n"
    with pytest.raises(queue.Empty):
        sink.read(timeout=0.04)


def test_expired_pending_target_is_not_sent_after_slow_write(make_worker, sink, wait_until):
    worker = make_worker(timeout_s=0.05)
    wait_until(lambda: worker.ready)
    sink.read()
    sink.write_gate.clear()
    worker.submit((1, 0, 0, 0))
    assert sink.write_started.wait(1.0)
    worker.submit((2, 0, 0, 0))
    time.sleep(0.09)
    sink.write_gate.set()
    assert sink.read()[0] == b"1,0,0,0\n"  # Already in flight; cannot be recalled.
    assert sink.read()[0] == ZERO
    with pytest.raises(queue.Empty):
        sink.read(timeout=0.04)


def test_rate_limit_coalesces_targets_and_explicit_stop_is_immediate(
    make_worker, sink, wait_until
):
    worker = make_worker(timeout_s=0.0, max_rate_hz=5.0)
    wait_until(lambda: worker.ready)
    sink.read()
    worker.submit((1, 0, 0, 0))
    _, first_at = sink.read()
    worker.submit((2, 0, 0, 0))
    with pytest.raises(queue.Empty):
        sink.read(timeout=0.04)
    worker.submit((3, 0, 0, 0))
    command, second_at = sink.read()
    assert command == b"3,0,0,0\n"
    assert second_at - first_at >= 0.18
    worker.submit((0, 0, 0, 0))
    assert sink.read(timeout=0.15)[0] == ZERO


def test_reset_and_reconnect_discard_commands(make_worker, sink, wait_until):
    sink.connect_gate.clear()
    worker = make_worker()
    assert sink.connect_started.wait(1.0)
    assert not worker.submit((10, 0, 0, 0))
    sink.connect_gate.set()
    wait_until(lambda: worker.ready)
    assert sink.read()[0] == ZERO
    sink.connect_gate.clear()
    sink.fail_next = True
    worker.submit((20, 0, 0, 0))
    wait_until(lambda: not worker.ready)
    assert not worker.submit((30, 0, 0, 0))
    sink.connect_gate.set()
    wait_until(lambda: worker.ready)
    assert sink.read()[0] == ZERO
    with pytest.raises(queue.Empty):
        sink.read(timeout=0.04)
    worker.submit((40, 0, 0, 0))
    assert sink.read()[0] == b"40,0,0,0\n"


def test_runtime_timeout_change_stops_existing_output(make_worker, sink, wait_until):
    worker = make_worker(timeout_s=0.0)
    wait_until(lambda: worker.ready)
    sink.read()
    worker.submit((80, 0, 0, 0))
    sink.read()
    time.sleep(0.08)
    worker.set_timeout(0.04)
    assert sink.read(timeout=0.2)[0] == ZERO


def test_shutdown_sends_zero_and_only_worker_owns_transport(make_worker, sink, wait_until):
    worker = make_worker()
    wait_until(lambda: worker.ready)
    sink.read()
    worker.submit((80, 0, 0, 0))
    sink.read()
    worker.close()
    assert sink.read()[0] == ZERO
    assert not sink.connected and not worker.ready
    assert len(sink.owners) == 1
    assert threading.get_ident() not in sink.owners
    assert not worker.submit((100, 0, 0, 0))


def test_shutdown_interrupts_board_reset(make_worker, sink):
    sink.connect_gate.clear()
    worker = make_worker()
    assert sink.connect_started.wait(1.0)
    finished = threading.Event()

    def close():
        worker.close()
        finished.set()

    closer = threading.Thread(target=close)
    closer.start()
    try:
        assert finished.wait(0.3), "shutdown waited for board reset to finish"
    finally:
        sink.connect_gate.set()
        closer.join(timeout=1.0)


@pytest.mark.parametrize("max_rate_hz", [0.0, 50.0])
def test_alternating_stop_does_not_accumulate_uart_backlog(
    make_worker, sink, wait_until, max_rate_hz
):
    worker = make_worker(timeout_s=0.0, max_rate_hz=max_rate_hz, baud=9600)
    wait_until(lambda: worker.ready)
    records = [sink.read()]
    for _ in range(20):
        worker.submit((255, 255, 255, 255))
        record = sink.read()
        assert record[0] == b"255,255,255,255\n"
        records.append(record)
        worker.submit((0, 0, 0, 0))
        record = sink.read()
        assert record[0] == ZERO
        records.append(record)

    # Independently model a UART draining the recorded bytes at 960 bytes/s.
    # One ordinary frame plus one urgent zero may be outstanding, never an
    # ever-growing sequence of old targets. The old bypass grew every cycle.
    wire_free_at = 0.0
    for data, queued_at in records:
        wire_free_at = max(wire_free_at, queued_at) + len(data) / 960.0
        assert wire_free_at - queued_at <= (16 + 8) / 960.0 + 0.001


@pytest.mark.parametrize("target", [(80, 0, 0, 0), (0, 0, 0, 0)])
def test_connection_zero_consumes_wire_budget(make_worker, sink, wait_until, target):
    worker = make_worker(timeout_s=0.0, baud=800)
    wait_until(lambda: worker.ready)
    initial_zero, initial_at = sink.read()
    assert initial_zero == ZERO
    worker.submit(target)
    command, sent_at = sink.read()
    assert command == ("{},{},{},{}\n".format(*target)).encode("ascii")
    assert sent_at - initial_at >= len(ZERO) * 10 / 800 - 0.001


def test_latest_target_replaces_pending_during_wire_wait(make_worker, sink, wait_until):
    worker = make_worker(timeout_s=0.0, baud=1600)
    wait_until(lambda: worker.ready)
    sink.read()
    worker.submit((255, 255, 255, 255))
    first, sent_at = sink.read()
    worker.submit((80, 0, 0, 0))
    worker.submit((120, 0, 0, 0))
    newest, newest_at = sink.read()
    assert newest == b"120,0,0,0\n"
    assert newest_at - sent_at >= len(first) * 10 / 1600 - 0.001
    with pytest.raises(queue.Empty):
        sink.read(timeout=0.03)


def test_watchdog_zero_keeps_wire_debt_for_next_target(make_worker, sink, wait_until):
    worker = make_worker(timeout_s=0.03, baud=1600)
    wait_until(lambda: worker.ready)
    sink.read()
    # Wait for the initial zero to drain; otherwise this very short-lived
    # test target may expire before its first transmission opportunity.
    time.sleep(0.06)
    worker.submit((255, 255, 255, 255))
    first, sent_at = sink.read()
    stop, stopped_at = sink.read()
    assert stop == ZERO
    assert stopped_at - sent_at < len(first) * 10 / 1600
    worker.set_timeout(0.0)
    worker.submit((80, 0, 0, 0))
    command, resumed_at = sink.read()
    assert command == b"80,0,0,0\n"
    assert resumed_at - sent_at >= (len(first) + len(ZERO)) * 10 / 1600 - 0.001


def test_60_hz_state_updates_have_no_default_50_hz_gate(monkeypatch, sink, wait_until):
    from haptic_band_ros import output_worker

    # Advance only the worker's clock, not Python/pytest's global clock.
    # This checks scheduling decisions at 60 Hz without relying on a lightly
    # loaded OS to hit 16.7 ms wall-clock deadlines in a performance test.
    clock = SimpleNamespace(now=100.0)
    monkeypatch.setattr(output_worker, "time", SimpleNamespace(monotonic=lambda: clock.now))
    worker = output_worker.OutputWorker(sink, timeout_s=0.5, baud=115200)
    completed = queue.Queue()
    send = worker._send

    def observe_send(values):
        send(values)
        # Signal only after the transmission budget has been recorded.
        completed.put(tuple(values))

    monkeypatch.setattr(worker, "_send", observe_send)
    worker.start()
    try:
        wait_until(lambda: worker.ready)
        assert completed.get(timeout=1.0) == (0, 0, 0, 0)
        for value in range(80, 100):
            clock.now += 1.0 / 60.0
            target = (value, 0, 0, 0)
            assert worker.submit(target)
            assert completed.get(timeout=0.3) == target
    finally:
        worker.close()


@pytest.mark.parametrize(
    "timeout_s,max_rate_hz",
    [
        (1e20, 0.0),
        (0.0, 1e-20),
        (0.0, 5e-324),
    ],
)
def test_large_waits_remain_interruptible_without_killing_worker(
    make_worker, sink, wait_until, timeout_s, max_rate_hz
):
    worker = make_worker(timeout_s=timeout_s, max_rate_hz=max_rate_hz)
    wait_until(lambda: worker.ready)
    sink.read()
    worker.submit((80, 0, 0, 0))
    assert sink.read()[0] == b"80,0,0,0\n"
    # Either a very distant watchdog or a pending rate-limited target must
    # wait interruptibly, not overflow the platform's Condition.wait limit.
    if max_rate_hz > 0:
        worker.submit((120, 0, 0, 0))
    with pytest.raises(queue.Empty):
        sink.read(timeout=0.03)
    assert worker.ready
    assert worker.submit((0, 0, 0, 0))
    assert sink.read()[0] == ZERO

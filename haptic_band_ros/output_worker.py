"""Send the latest motor target without blocking ROS callbacks."""

import threading
import time


ZERO = (0, 0, 0, 0)


class OutputWorker:
    """Own one transport, a single pending target, and a monotonic watchdog.

    Only this thread accesses the transport. Producers replace the pending
    target; they never wait for connection or transmission. Commands received
    while disconnected/resetting are discarded, including across reconnects.
    """

    def __init__(self, sink, timeout_s, max_rate_hz=0.0, log=None,
                 reconnect_s=1.0, baud=None):
        self.sink = sink
        self._timeout_s = timeout_s
        self._interval = 1.0 / max_rate_hz if max_rate_hz > 0 else 0.0
        self._seconds_per_byte = 10.0 / baud if baud is not None else 0.0
        self._wire_busy_until = 0.0
        self._log = log or (lambda level, message: None)
        self._reconnect_s = reconnect_s
        self._condition = threading.Condition()
        self._stop = threading.Event()
        self._ready = False
        self._pending = None
        self._last_received = 0.0
        self._thread = threading.Thread(
            target=self._run, name='haptic-output', daemon=True)

    @property
    def ready(self):
        """Return whether reset and the initial zero command have completed."""
        with self._condition:
            return self._ready

    @property
    def timeout_s(self):
        """Return the currently effective timeout."""
        with self._condition:
            return self._timeout_s

    def start(self):
        """Start the transport owner thread."""
        self._thread.start()

    def submit(self, values):
        """Replace the pending target, returning False while disconnected."""
        with self._condition:
            if not self._ready or self._stop.is_set():
                return False
            self._last_received = time.monotonic()
            self._pending = tuple(values)
            self._condition.notify()
            return True

    def set_timeout(self, timeout_s):
        """Apply an already validated timeout and immediately recheck expiry."""
        with self._condition:
            self._timeout_s = timeout_s
            self._condition.notify()

    def close(self):
        """Wake the worker, send a final zero if possible, and join it."""
        self._stop.set()
        with self._condition:
            self._condition.notify()
        if self._thread.ident is not None:
            self._thread.join()

    def _disconnect(self):
        with self._condition:
            self._ready = False
            self._pending = None
        self.sink.close()

    def _send(self, values):
        data = ('{},{},{},{}\n'.format(*values)).encode('ascii')
        self.sink.send(data)
        # A successful write only queues bytes. Estimate their 8N1 wire time
        # conservatively from write completion. Urgent zeros add to existing
        # debt; they must not let later nonzero targets overfill the UART.
        self._wire_busy_until = max(
            time.monotonic(), self._wire_busy_until
        ) + len(data) * self._seconds_per_byte

    def _run(self):
        active = False
        next_send = 0.0
        last_connect_warning = float('-inf')
        try:
            while not self._stop.is_set():
                if not self.sink.connected:
                    try:
                        self._wire_busy_until = 0.0
                        name = self.sink.connect(self._stop)
                        if self._stop.is_set():
                            break
                        self._send(ZERO)
                    except OSError as exc:
                        self._disconnect()
                        now = time.monotonic()
                        if not self._stop.is_set() and now - last_connect_warning >= 10.0:
                            self._log('warning', f'Not connected ({exc}); retrying...')
                            last_connect_warning = now
                        self._stop.wait(self._reconnect_s)
                        continue
                    with self._condition:
                        self._pending = None
                        self._ready = True
                    active = False
                    next_send = 0.0
                    self._log('info', f'Connected: {name}; ready for new commands')

                with self._condition:
                    if self._stop.is_set():
                        break
                    now = time.monotonic()
                    timeout = self._timeout_s
                    expired = timeout > 0 and now - self._last_received >= timeout
                    if expired:
                        self._pending = None

                    timed_out = active and expired
                    send_ready_at = max(next_send, self._wire_busy_until)
                    if timed_out:
                        values = ZERO
                    elif self._pending is not None and (
                        now >= send_ready_at or (active and not any(self._pending))
                    ):
                        values = self._pending
                        self._pending = None
                    else:
                        # No periodic output/polling clock: wake on a new target,
                        # parameter update, shutdown, or the earliest deadline.
                        deadlines = []
                        if self._pending is not None:
                            deadlines.append(send_ready_at)
                        if active and timeout > 0:
                            deadlines.append(self._last_received + timeout)
                        delay = (min(threading.TIMEOUT_MAX, max(0.0, min(deadlines) - now))
                                 if deadlines else None)
                        self._condition.wait(delay)
                        continue

                try:
                    self._send(values)
                except OSError as exc:
                    self._log('error', f'Send failed ({exc}); will reconnect.')
                    self._disconnect()
                    active = False
                    self._stop.wait(self._reconnect_s)
                else:
                    active = any(values)
                    next_send = time.monotonic() + self._interval
                    if timed_out:
                        self._log('info', 'Command timeout: stopping motors.')
        finally:
            with self._condition:
                self._ready = False
                self._pending = None
            try:
                if self.sink.connected:
                    self._send(ZERO)
            except OSError as exc:
                self._log('warning', f'Could not send shutdown stop: {exc}')
            finally:
                self.sink.close()

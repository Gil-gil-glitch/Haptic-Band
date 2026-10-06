"""Local TCP server for external control.

Existing teleoperation / control code can send "top,right,bottom,left\\n"
lines here. The app forwards them to the Arduino and updates the UI, so the UI
always reflects exactly what's being sent.

Callbacks are invoked on background threads; the GUI marshals them onto the
Tk thread (see MainApp._poll_events).
"""

import socket
import threading


class CommandServer:
    def __init__(self, port, on_command, on_status=None):
        self.port = port
        self._on_command = on_command  # (top, right, bottom, left) -> None
        self._on_status = on_status  # (message) -> None
        self._running = False
        self._server = None
        self._thread = None

    def start(self):
        self._running = True
        self._thread = threading.Thread(
            target=self._accept_loop, name="command-server-accept", daemon=True
        )
        self._thread.start()

    def _status(self, message):
        if self._on_status:
            self._on_status(message)

    def _accept_loop(self):
        try:
            self._server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            self._server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            self._server.bind(("127.0.0.1", self.port))
            self._server.listen(1)
            self._server.settimeout(0.5)
        except OSError as e:
            self._status(f"Could not start command server on port {self.port}: {e}")
            return

        self._status(f"Listening for external control on localhost:{self.port}")
        while self._running:
            try:
                client, addr = self._server.accept()
            except socket.timeout:
                continue
            except OSError:
                break
            with client:
                self._status(f"Client connected: {addr[0]}")
                try:
                    self._handle_client(client)
                except OSError as e:
                    if self._running:
                        self._status(f"Client error: {e}")
                self._status("Client disconnected")

    def _handle_client(self, client):
        client.settimeout(0.5)
        buf = b""
        while self._running:
            try:
                chunk = client.recv(1024)
            except socket.timeout:
                continue
            if not chunk:
                break
            buf += chunk
            while b"\n" in buf:
                line, buf = buf.split(b"\n", 1)
                self._parse_and_dispatch(line.decode("utf-8", errors="replace"))

    def _parse_and_dispatch(self, line):
        trimmed = line.strip()
        if not trimmed:
            return
        parts = trimmed.split(",")
        if len(parts) != 4:
            self._status(f'Ignored malformed command: "{trimmed}"')
            return
        try:
            top, right, bottom, left = (int(p.strip()) for p in parts)
        except ValueError:
            self._status(f'Ignored malformed command: "{trimmed}"')
            return
        self._on_command(top, right, bottom, left)

    def stop(self):
        self._running = False
        try:
            if self._server is not None:
                self._server.close()
        except OSError:
            pass

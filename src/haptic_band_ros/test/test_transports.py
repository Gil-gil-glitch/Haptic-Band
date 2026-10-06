"""Check the actual CSV transports using a pseudo-terminal and loopback TCP."""

import os
import pty
import select
import socket
import threading

from haptic_band_ros.haptic_bridge import SerialSink, SocketSink


def test_serial_protocol_on_pseudo_terminal():
    master, slave = pty.openpty()
    sink = SerialSink(os.ttyname(slave), 9600, 0.0, 0.1)
    try:
        sink.connect(threading.Event())
        sink.send(b'64,128,0,32\n')
        assert select.select([master], [], [], 1.0)[0]
        assert os.read(master, 1024) == b'64,128,0,32\n'
    finally:
        sink.close()
        os.close(master)
        os.close(slave)


def test_gui_socket_protocol_on_loopback():
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
        server.bind(('127.0.0.1', 0))
        server.listen(1)
        server.settimeout(1.0)
        sink = SocketSink('127.0.0.1', server.getsockname()[1], 0.1)
        try:
            sink.connect(threading.Event())
            client, _ = server.accept()
            with client:
                client.settimeout(1.0)
                sink.send(b'64,128,0,32\n')
                received = b''
                while not received.endswith(b'\n'):
                    received += client.recv(1024)
                assert received == b'64,128,0,32\n'
        finally:
            sink.close()

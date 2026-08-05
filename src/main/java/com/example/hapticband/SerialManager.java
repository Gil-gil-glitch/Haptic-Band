package com.example.hapticband;


import com.fazecast.jSerialComm.SerialPort;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Owns the connection to the Arduino. Only one process can hold a COM port
 * open at a time, so this app is the single owner of the serial link -
 * the Python control logic talks to CommandServer over a local socket instead.
 *
 * Sends the same "top,right,bottom,left\n" line the Arduino sketch expects.
 */

public class SerialManager {

    private SerialPort port;
    private OutputStream out;

    /** Returns the system names of available serial ports, e.g. ["COM3", "COM4"]. */
    public String[] listPorts() {
        SerialPort[] ports = SerialPort.getCommPorts();
        String[] names = new String[ports.length];
        for (int i = 0; i < ports.length; i++) {
            names[i] = ports[i].getSystemPortName();
        }
        return names;
    }

    public boolean connect(String portName, int baudRate) {
        port = SerialPort.getCommPort(portName);
        port.setBaudRate(baudRate);
        port.setNumDataBits(8);
        port.setNumStopBits(SerialPort.ONE_STOP_BIT);
        port.setParity(SerialPort.NO_PARITY);
        port.setComPortTimeouts(SerialPort.TIMEOUT_WRITE_BLOCKING, 1000, 0);

        boolean opened = port.openPort();
        if (opened) {
            out = port.getOutputStream();
            try {
                // Mirrors time.sleep(2) in the original script - gives the
                // Arduino time to reset after the serial connection opens.
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        return opened;
    }

    public boolean isConnected() {
        return port != null && port.isOpen();
    }

    public synchronized void sendMotorValues(int top, int right, int bottom, int left) throws Exception {
        if (!isConnected()) {
            throw new IllegalStateException("Serial port is not open");
        }
        int t = clamp(top);
        int r = clamp(right);
        int b = clamp(bottom);
        int l = clamp(left);
        String command = t + "," + r + "," + b + "," + l + "\n";
        out.write(command.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    public void disconnect() {
        try {
            if (out != null) out.close();
        } catch (Exception ignored) {
        }
        if (port != null && port.isOpen()) {
            port.closePort();
        }
    }
}

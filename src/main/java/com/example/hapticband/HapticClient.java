package com.example.hapticband;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Client library for programmatically controlling the Haptic Wristband
 * via the JavaFX application's background CommandServer. This again
 * does not directly connect to COM4, but just listnes on the socket of
 * CommandServer
 */
public class HapticClient implements AutoCloseable {

    private final String host;
    private final int port;
    private Socket socket;
    private OutputStream out;

    public HapticClient() {
        this("localhost", 5050);
    }

    public HapticClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /**
     * Connects to the JavaFX application's background socket server.
     */
    public void connect() throws IOException {
        if (isConnected()) {
            return;
        }
        this.socket = new Socket(host, port);
        this.out = socket.getOutputStream();
    }

    public boolean isConnected() {
        return socket != null && !socket.isClosed() && socket.isConnected();
    }

    /**
     * Sends raw PWM values (0-255) for all four motors.
     * Values are automatically clamped to the valid 0-255 range.
     */
    public synchronized void sendRaw(int top, int right, int bottom, int left) throws IOException {
        if (!isConnected()) {
            throw new IOException("HapticClient is not connected to server.");
        }

        int t = clamp(top);
        int r = clamp(right);
        int b = clamp(bottom);
        int l = clamp(left);

        String command = t + "," + r + "," + b + "," + l + "\n";
        out.write(command.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /**
     * Pulses a single motor for a specified duration in milliseconds, then stops all motors.
     *
     * @param direction "top", "right", "bottom", or "left"
     * @param pwm       Intensity (0-255)
     * @param durationMs Pulse duration in milliseconds
     */
    public void pulseDirection(String direction, int pwm, long durationMs) throws IOException, InterruptedException {
        int t = direction.equalsIgnoreCase("top") ? pwm : 0;
        int r = direction.equalsIgnoreCase("right") ? pwm : 0;
        int b = direction.equalsIgnoreCase("bottom") ? pwm : 0;
        int l = direction.equalsIgnoreCase("left") ? pwm : 0;

        sendRaw(t, r, b, l);
        Thread.sleep(durationMs);
        stopAll();
    }

    /**
     * Stops all motors immediately.
     */
    public void stopAll() throws IOException {
        sendRaw(0, 0, 0, 0);
    }

    private int clamp(int val) {
        return Math.max(0, Math.min(255, val));
    }

    /**
     * Stops motors and closes the socket connection.
     */
    @Override
    public void close() {
        try {
            if (isConnected()) {
                stopAll();
                out.close();
                socket.close();
            }
        } catch (IOException ignored) {
        } finally {
            socket = null;
            out = null;
        }
    }
}
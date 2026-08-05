package com.example.hapticband;

import javafx.application.Platform;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Listens on localhost so your existing teleoperation / control code can send
 * the same "top,right,bottom,left\n" lines  it used to write directly to the
 * serial port in the Python implementation. This app forwards them to the
 * Arduino and updates the UI, so the UI always reflects exactly what's being
 * sent - regardless of whether the command came from this app's own controls
 * or from an external process.
 */

public class CommandServer {

    public interface CommandListener {
        void onCommand(int top, int right, int bottom, int left);
    }

    public interface StatusListener {
        void onStatus(String message);
    }

    private final int port;
    private final CommandListener commandListener;
    private final StatusListener statusListener;
    private volatile boolean running = false;
    private ServerSocket serverSocket;
    private Thread acceptThread;

    public CommandServer(int port, CommandListener commandListener, StatusListener statusListener) {
        this.port = port;
        this.commandListener = commandListener;
        this.statusListener = statusListener;
    }

    public void start() {
        running = true;
        acceptThread = new Thread(this::acceptLoop, "command-server-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private void acceptLoop() {
        try {
            serverSocket = new ServerSocket(port);
            notifyStatus("Listening for external control on localhost:" + port);
            while (running) {
                try (Socket client = serverSocket.accept()) {
                    notifyStatus("Client connected: " + client.getInetAddress().getHostAddress());
                    handleClient(client);
                    notifyStatus("Client disconnected");
                } catch (IOException e) {
                    if (running) {
                        notifyStatus("Client error: " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            notifyStatus("Could not start command server on port " + port + ": " + e.getMessage());
        }
    }

    private void handleClient(Socket client) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (running && (line = reader.readLine()) != null) {
                parseAndDispatch(line);
            }
        }
    }

    private void parseAndDispatch(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return;

        String[] parts = trimmed.split(",");
        if (parts.length != 4) {
            notifyStatus("Ignored malformed command: \"" + trimmed + "\"");
            return;
        }
        try {
            int top = Integer.parseInt(parts[0].trim());
            int right = Integer.parseInt(parts[1].trim());
            int bottom = Integer.parseInt(parts[2].trim());
            int left = Integer.parseInt(parts[3].trim());
            Platform.runLater(() -> commandListener.onCommand(top, right, bottom, left));
        } catch (NumberFormatException e) {
            notifyStatus("Ignored malformed command: \"" + trimmed + "\"");
        }
    }

    private void notifyStatus(String message) {
        if (statusListener != null) {
            Platform.runLater(() -> statusListener.onStatus(message));
        }
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
    }
}

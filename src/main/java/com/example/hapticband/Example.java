package com.example.hapticband;


public class Example {

    public static void main(String[] args) {
        System.out.println("Connecting to Haptic Command Server...");

        // Automatically connects and safely disconnects upon exit
        try (HapticClient client = new HapticClient("localhost", 5050)) {
            client.connect();

            System.out.println("Pulsing Top motor...");
            client.pulseDirection("top", 255, 1000); // 1 second pulse

            System.out.println("Pulsing Right motor...");
            client.pulseDirection("right", 127, 1000);

            System.out.println("Global 50% PWM test...");
            client.sendRaw(127, 127, 127, 127);
            Thread.sleep(1000);

            client.stopAll();
            System.out.println("Test complete.");

        } catch (Exception e) {
            System.err.println("Error communicating with Haptic server: " + e.getMessage());
        }
    }
}
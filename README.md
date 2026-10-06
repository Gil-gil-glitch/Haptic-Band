# Haptic Band

ROS 2 control for a four-motor haptic wristband, with an optional Tkinter GUI.
Each message sets the latest `[top, right, bottom, left]` PWM values (0–255).

## Build

Requires ROS 2 Jazzy and `python3-serial`. The optional GUI needs `python3-tk`.
Clone this repository into your workspace's `src/` directory, then:

```bash
source /opt/ros/jazzy/setup.bash
cd ~/ros2_ws
colcon build --packages-select haptic_band_ros --symlink-install
source install/setup.bash
```

The repository root is the ROS package. Driver code is in `haptic_band_ros/`,
the GUI in `haptic_band_ros/gui/`, and standalone demos in `examples/`.

## Run and control

Source ROS and the workspace in each terminal. Find the wristband's port:

```bash
python3 -m serial.tools.list_ports -v
```

Start the driver, replacing the port as needed:

```bash
ros2 run haptic_band_ros haptic_bridge --ros-args \
  -p port:=/dev/ttyUSB0 -p baud:=9600 -p timeout_s:=0.5
```

The firmware must use the same baud rate and accept `top,right,bottom,left\n`.
Firmware is not included. On Ubuntu, serial access may require membership in
`dialout`: `sudo usermod -aG dialout "$USER"`, then log out and back in.

Wait for `Connected`, then publish from another terminal:

```bash
ros2 topic pub -r 60 --qos-reliability best_effort --qos-depth 1 \
  /haptic/motors std_msgs/msg/Int32MultiArray '{data: [80, 0, 0, 0]}'
```

- New targets replace pending older targets; intermediate states, including
  zero, may be skipped. This interface does not preserve pulse sequences.
- Stop publishing to trigger the default 0.5-second timeout, or send
  `{data: [0, 0, 0, 0]}` after stopping the continuous publisher.
- Set `timeout_s:=0.0` to disable automatic stopping. Runtime changes work with
  `ros2 param set /haptic_bridge timeout_s 0.3`.
- There is no default software frequency cap (`max_rate_hz:=0.0`). Serial
  transmission is paced by byte count; 9600 baud is near its limit for full
  16-byte commands at 60 Hz. Higher baud rates require matching firmware.

The host watchdog cannot stop motors after a broken connection or process
crash; that requires a watchdog in the firmware.

## Optional GUI and examples

```bash
ros2 run haptic_band_ros haptic_gui
```

Alternatively, use `python3 -m haptic_band_ros.gui`. Connect the serial port in
the GUI before using ROS forwarding:

```bash
ros2 run haptic_band_ros haptic_bridge --ros-args -p output:=gui_socket
```

Only one process should own the serial port. Use direct serial mode for
latency-sensitive control. The GUI provides manual sliders, patterns, and CSV
logging; its gauges show commanded values, not measured vibration.

After sourcing the workspace, example usage is available with:

```bash
python3 examples/serial_control.py --help
python3 examples/gui_client.py --help
```

## Development

From the repository root, after sourcing ROS:

```bash
python3 -m pytest -q test/test_output_worker.py test/test_haptic_bridge.py test/test_transports.py
```

Tests use simulated outputs, not real hardware.

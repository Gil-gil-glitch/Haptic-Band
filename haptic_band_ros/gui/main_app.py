"""Haptic Wristband Command Center (tkinter port of the JavaFX MainApp)."""
import queue
import tkinter as tk
from dataclasses import dataclass
from datetime import datetime
from tkinter import filedialog, ttk

from .command_server import CommandServer
from .motor_gauge import MotorGauge
from .serial_manager import SerialManager

COMMAND_SERVER_PORT = 5050

BG, PANEL, INPUT = "#23272a", "#282b30", "#1e2124"
BTN, BTN_HOVER, BORDER = "#2c2f33", "#40444b", "#4f545c"
TEXT, MUTED, GREEN, RED, BLURPLE = "#ffffff", "#b9bbbe", "#43b581", "#f04747", "#7289da"

DIRECTIONS = ["Top (Forward)", "Right", "Bottom (Back)", "Left", "All Motors", "Pause (None)"]


@dataclass
class PatternStep:
    direction: str
    intensity: int
    duration_ms: int

    def __str__(self):
        if self.direction == "Pause (None)":
            return f"Pause for {self.duration_ms} ms"
        return f"{self.direction} at {self.intensity} PWM for {self.duration_ms} ms"


def _label(parent, text, **kw):
    kw.setdefault("bg", parent["bg"])
    kw.setdefault("fg", MUTED)
    return tk.Label(parent, text=text, **kw)


def _button(parent, text, command, fg=TEXT, border=BORDER, **kw):
    b = tk.Button(parent, text=text, command=command, bg=BTN, fg=fg,
                  activebackground=BTN_HOVER, activeforeground=fg, relief="flat",
                  highlightthickness=1, highlightbackground=border,
                  padx=10, pady=4, cursor="hand2", **kw)
    b.bind("<Enter>", lambda e: b.config(bg=BTN_HOVER))
    b.bind("<Leave>", lambda e: b.config(bg=BTN))
    return b


def _panel(parent, title):
    f = tk.LabelFrame(parent, text=title, bg=PANEL, fg=TEXT,
                      font=("Segoe UI", 9, "bold"), bd=1, relief="solid", padx=10, pady=8)
    return f


class MainApp:
    def __init__(self, root: tk.Tk):
        self.root = root
        self.serial = SerialManager()
        self.server = None
        self.events = queue.Queue()  # thread-safe hand-off from server thread

        self.csv_file = None
        self.csv_var = tk.BooleanVar(value=False)
        self.connected_var = tk.StringVar(value="Disconnected")
        self.server_var = tk.StringVar(value="Command server not started")
        self.baud_var = tk.StringVar(value="9600")  # Arduino default
        self.port_var = tk.StringVar()

        self.sequence_jobs = []  # pending after() ids (cancellable)
        self.steps = []          # list[PatternStep]

        root.title("Haptic Wristband Command Center")
        root.geometry("1050x780")
        root.configure(bg=BG, padx=15, pady=15)
        root.protocol("WM_DELETE_WINDOW", self.shutdown)
        root.columnconfigure(0, weight=1)
        root.rowconfigure(1, weight=1)

        self._build_connection_bar().grid(row=0, column=0, columnspan=2, sticky="w", pady=(0, 20))
        self._build_center().grid(row=1, column=0, sticky="nsew", padx=(0, 15))
        self._build_pattern_editor().grid(row=1, column=1, sticky="ns")
        self._build_bottom().grid(row=2, column=0, columnspan=2, sticky="ew", pady=(20, 0))

        self.refresh_ports()
        self.start_command_server()
        self.root.after(20, self._poll_events)

    # ------------------------------------------------------------ UI build
    def _build_connection_bar(self):
        bar = tk.Frame(self.root, bg=BG)
        _label(bar, "COM Port:").pack(side="left", padx=(0, 6))
        self.port_combo = ttk.Combobox(bar, textvariable=self.port_var, width=12, state="readonly")
        self.port_combo.pack(side="left", padx=(0, 12))
        _button(bar, "Refresh", self.refresh_ports).pack(side="left", padx=(0, 12))
        _label(bar, "Baud Rate:").pack(side="left", padx=(0, 6))
        tk.Entry(bar, textvariable=self.baud_var, width=8, bg=INPUT, fg=TEXT,
                 insertbackground=TEXT, relief="flat").pack(side="left", padx=(0, 12))
        self.connect_btn = _button(bar, "Connect", self.toggle_connection)
        self.connect_btn.pack(side="left", padx=(0, 12))
        self.status_lbl = tk.Label(bar, textvariable=self.connected_var, bg=BG, fg=RED,
                                   font=("Segoe UI", 9, "bold"))
        self.status_lbl.pack(side="left")
        return bar

    def _build_center(self):
        center = tk.Frame(self.root, bg=BG)

        cross_panel = _panel(center, "Live Motor Status")
        cross_panel.pack(fill="x", pady=(0, 20))
        cross = tk.Frame(cross_panel, bg=PANEL)
        cross.pack()
        self.top_gauge, self.right_gauge = MotorGauge(cross, "TOP"), MotorGauge(cross, "RIGHT")
        self.bottom_gauge, self.left_gauge = MotorGauge(cross, "BOTTOM"), MotorGauge(cross, "LEFT")
        compass = tk.Frame(cross, bg=BTN, highlightthickness=1, highlightbackground=BLURPLE,
                           width=80, height=80)
        compass.pack_propagate(False)
        tk.Label(compass, text="WRIST", bg=BTN, fg=BLURPLE,
                 font=("Segoe UI", 9, "bold")).pack(expand=True)
        self.top_gauge.grid(row=0, column=1, padx=15, pady=5)
        self.left_gauge.grid(row=1, column=0, padx=15, pady=5)
        compass.grid(row=1, column=1, padx=15, pady=5)
        self.right_gauge.grid(row=1, column=2, padx=15, pady=5)
        self.bottom_gauge.grid(row=2, column=1, padx=15, pady=5)

        # Manual overrides
        manual = _panel(center, "Manual Overrides")
        manual.pack(fill="x", pady=(0, 20))
        self.sliders = {}
        for i, (key, name) in enumerate([("top", "Top (Forward)"), ("right", "Right"),
                                         ("bottom", "Bottom (Back)"), ("left", "Left")]):
            var = tk.IntVar(value=0)
            self.sliders[key] = var
            _label(manual, name, width=12, anchor="w").grid(row=i, column=0, pady=6)
            tk.Scale(manual, from_=0, to=255, orient="horizontal", variable=var, length=220,
                     showvalue=False, bg=PANEL, troughcolor=INPUT, highlightthickness=0,
                     activebackground=BLURPLE).grid(row=i, column=1, padx=10)
            _label(manual, "", textvariable=var, width=4).grid(row=i, column=2)
        btns = tk.Frame(manual, bg=PANEL)
        btns.grid(row=4, column=0, columnspan=3, sticky="w", pady=(10, 0))
        _button(btns, "Apply Overrides", self.apply_sliders).pack(side="left", padx=(0, 12))
        _button(btns, "Halt All Motors", self.halt_all, border=RED).pack(side="left")

        # Presets
        presets = _panel(center, "Quick Triggers")
        presets.pack(fill="x")
        _button(presets, "Pulse Forward",
                lambda: self.apply_and_send(255, 0, 0, 0, "preset")).pack(side="left", padx=(0, 10))
        _button(presets, "Global 50%",
                lambda: self.apply_and_send(127, 127, 127, 127, "preset")).pack(side="left", padx=(0, 10))
        _button(presets, "Run Demo Sweep", self.run_test_sequence).pack(side="left")
        return center

    def _build_pattern_editor(self):
        pane = _panel(self.root, "Pattern Builder")
        pane.configure(width=360)

        _label(pane, "1. Construct Sequence Step-by-Step").pack(anchor="w", pady=(0, 8))

        row1 = tk.Frame(pane, bg=PANEL)
        row1.pack(anchor="w", pady=(0, 8))
        _label(row1, "Dir:").pack(side="left", padx=(0, 6))
        self.dir_combo = ttk.Combobox(row1, values=DIRECTIONS, width=13, state="readonly")
        self.dir_combo.set(DIRECTIONS[0])
        self.dir_combo.pack(side="left", padx=(0, 8))
        _label(row1, "PWM:").pack(side="left", padx=(0, 6))
        self.pwm_spin = ttk.Spinbox(row1, from_=0, to=255, increment=5, width=5)
        self.pwm_spin.set(127)
        self.pwm_spin.pack(side="left")

        row2 = tk.Frame(pane, bg=PANEL)
        row2.pack(anchor="w", pady=(0, 8))
        _label(row2, "Time (ms):").pack(side="left", padx=(0, 6))
        self.dur_spin = ttk.Spinbox(row2, from_=50, to=5000, increment=50, width=6)
        self.dur_spin.set(500)
        self.dur_spin.pack(side="left", padx=(0, 8))
        _button(row2, "Add", self.add_step).pack(side="left")

        self.step_list = tk.Listbox(pane, height=12, bg=INPUT, fg=TEXT, selectbackground=BLURPLE,
                                    selectforeground="white", highlightbackground=BORDER,
                                    relief="flat", exportselection=False)
        self.step_list.pack(fill="both", expand=True, pady=(0, 8))

        edit = tk.Frame(pane, bg=PANEL)
        edit.pack(anchor="w", pady=(0, 8))
        _button(edit, "Remove Selected", self.remove_step).pack(side="left", padx=(0, 8))
        _button(edit, "Clear All", self.clear_steps).pack(side="left")

        _label(pane, "2. Save / Execute").pack(anchor="w", pady=(0, 8))
        files = tk.Frame(pane, bg=PANEL)
        files.pack(anchor="w", pady=(0, 8))
        _button(files, "Save...", self.save_pattern).pack(side="left", padx=(0, 8))
        _button(files, "Load...", self.load_pattern).pack(side="left")

        _button(pane, "▶ Play Sequence", self.play_pattern, fg=GREEN, border=GREEN,
                font=("Segoe UI", 9, "bold")).pack(fill="x")
        return pane

    def _build_bottom(self):
        box = tk.Frame(self.root, bg=BG)
        _label(box, "System Log").pack(anchor="w")
        self.log_area = tk.Text(box, height=6, bg=INPUT, fg=GREEN, relief="flat",
                                highlightthickness=1, highlightbackground=BORDER, state="disabled")
        self.log_area.pack(fill="x", pady=8)
        tk.Checkbutton(box, text="Log commands to CSV", variable=self.csv_var,
                       command=self.on_csv_toggle, bg=BG, fg=MUTED, selectcolor=INPUT,
                       activebackground=BG, activeforeground=MUTED).pack(anchor="w")
        tk.Label(box, textvariable=self.server_var, bg=BG, fg="#72767d",
                 font=("Segoe UI", 9, "italic")).pack(anchor="w", pady=(8, 0))
        return box

    # -------------------------------------------------------------- Serial
    def refresh_ports(self):
        ports = self.serial.list_ports()
        selected = self.port_var.get()
        self.port_combo["values"] = ports
        if selected in ports:
            self.port_var.set(selected)
        elif ports:
            self.port_var.set(ports[0])
        else:
            self.port_var.set("")

    def toggle_connection(self):
        if self.serial.is_connected():
            self.serial.disconnect()
            self._set_status("Disconnected", RED)
            self.connect_btn.config(text="Connect")
            self.log("Disconnected from serial port.")
            return

        port = self.port_var.get()
        if not port:
            self.log("No serial port selected.")
            return
        try:
            baud = int(self.baud_var.get().strip())
        except ValueError:
            self.log(f"Invalid baud rate: {self.baud_var.get()}")
            return

        self.log(f"Opening {port}...")
        self.root.update_idletasks()
        if self.serial.connect(port, baud):  # blocks ~2s for Arduino reset
            self._set_status(f"Connected: {port} @ {baud}", GREEN)
            self.connect_btn.config(text="Disconnect")
            self.log(f"Connected to {port} at {baud} baud.")
        else:
            self._set_status("Failed to connect", RED)
            self.log(f"Failed to open {port}.")

    def _set_status(self, text, color):
        self.connected_var.set(text)
        self.status_lbl.config(fg=color)

    # ------------------------------------------------------------ Commands
    def apply_and_send(self, top, right, bottom, left, source):
        self.top_gauge.set_pwm(top)
        self.right_gauge.set_pwm(right)
        self.bottom_gauge.set_pwm(bottom)
        self.left_gauge.set_pwm(left)

        if self.serial.is_connected():
            try:
                self.serial.send_motor_values(top, right, bottom, left)
                self.log(f"[{source}] Sent T={top} R={right} B={bottom} L={left}")
            except Exception as ex:
                self.log(f"Send failed: {ex}")
        else:
            self.log(f"[{source}] (not connected) T={top} R={right} B={bottom} L={left}")
        self.write_csv_row(top, right, bottom, left, source)

    def apply_sliders(self):
        s = self.sliders
        self.apply_and_send(s["top"].get(), s["right"].get(),
                            s["bottom"].get(), s["left"].get(), "manual")

    def halt_all(self):
        for var in self.sliders.values():
            var.set(0)
        self.apply_and_send(0, 0, 0, 0, "manual")

    def run_test_sequence(self):
        self.log("Running demo sweep...")
        steps = [(255, 0, 0, 0), (0, 255, 0, 0), (0, 0, 255, 0), (0, 0, 0, 255), (0, 0, 0, 0)]
        self._cancel_jobs()
        self.apply_and_send(*steps[0], "sequence")
        for i, step in enumerate(steps[1:], start=1):
            self._schedule(300 * i, lambda s=step: self.apply_and_send(*s, "sequence"))
        self._schedule(300 * (len(steps) - 1), lambda: self.log("Demo sweep complete."))

    def _schedule(self, delay_ms, fn):
        self.sequence_jobs.append(self.root.after(int(delay_ms), fn))

    def _cancel_jobs(self):
        for job in self.sequence_jobs:
            self.root.after_cancel(job)
        self.sequence_jobs.clear()

    # ------------------------------------------------------ Pattern builder
    def add_step(self):
        try:
            pwm = max(0, min(255, int(self.pwm_spin.get())))
            dur = max(1, int(self.dur_spin.get()))
        except ValueError:
            self.log("PWM and time must be whole numbers.")
            return
        step = PatternStep(self.dir_combo.get(), pwm, dur)
        self.steps.append(step)
        self.step_list.insert("end", str(step))

    def remove_step(self):
        sel = self.step_list.curselection()
        if sel:
            self.step_list.delete(sel[0])
            del self.steps[sel[0]]

    def clear_steps(self):
        self.steps.clear()
        self.step_list.delete(0, "end")

    def play_pattern(self):
        if not self.steps:
            return
        self._cancel_jobs()
        delay = 0
        for step in self.steps:
            pwm = step.intensity
            d = step.direction
            if d == "Pause (None)":
                t = r = b = l = 0
            else:
                all_m = d == "All Motors"
                t = pwm if ("Top" in d or all_m) else 0
                r = pwm if ("Right" in d or all_m) else 0
                b = pwm if ("Bottom" in d or all_m) else 0
                l = pwm if ("Left" in d or all_m) else 0
            self._schedule(delay, lambda v=(t, r, b, l): self.apply_and_send(*v, "custom_sequence"))
            delay += step.duration_ms
        self._schedule(delay, lambda: self.apply_and_send(0, 0, 0, 0, "sequence_end"))
        self.log(f"Playing custom sequence ({len(self.steps)} steps)...")

    def save_pattern(self):
        path = filedialog.asksaveasfilename(
            title="Save Haptic Pattern", defaultextension=".hpt",
            filetypes=[("Haptic Files", "*.hpt")])
        if not path:
            return
        try:
            with open(path, "w", encoding="utf-8") as f:
                for s in self.steps:
                    f.write(f"{s.direction},{s.intensity},{s.duration_ms}\n")
            self.log(f"Saved pattern to {path.split('/')[-1].split(chr(92))[-1]}")
        except OSError as e:
            self.log(f"Failed to save pattern: {e}")

    def load_pattern(self):
        path = filedialog.askopenfilename(
            title="Load Haptic Pattern", filetypes=[("Haptic Files", "*.hpt")])
        if not path:
            return
        try:
            loaded = []
            with open(path, encoding="utf-8") as f:
                for line in f:
                    parts = line.strip().split(",")
                    if len(parts) == 3:
                        loaded.append(PatternStep(parts[0], int(parts[1]), int(parts[2])))
            self.clear_steps()
            for s in loaded:
                self.steps.append(s)
                self.step_list.insert("end", str(s))
            self.log(f"Loaded pattern: {path.split('/')[-1].split(chr(92))[-1]}")
        except Exception:
            self.log("Failed to load pattern. Check file format.")

    # -------------------------------------------------------- Command server
    def start_command_server(self):
        self.server = CommandServer(
            COMMAND_SERVER_PORT,
            on_command=lambda t, r, b, l: self.events.put(("cmd", (t, r, b, l))),
            on_status=lambda msg: self.events.put(("status", msg)),
        )
        self.server.start()

    def _poll_events(self):
        """Drain events from the server thread on the Tk thread."""
        try:
            while True:
                kind, payload = self.events.get_nowait()
                if kind == "cmd":
                    self.apply_and_send(*payload, "external")
                else:
                    self.server_var.set(payload)
                    self.log(payload)
        except queue.Empty:
            pass
        self.root.after(20, self._poll_events)

    # --------------------------------------------------------------- Logging
    def log(self, message):
        stamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S.%f")[:-3]
        self.log_area.config(state="normal")
        self.log_area.insert("end", f"[{stamp}] {message}\n")
        self.log_area.see("end")
        self.log_area.config(state="disabled")

    def on_csv_toggle(self):
        if self.csv_var.get():
            self.choose_csv_file()
        else:
            self.close_csv()

    def choose_csv_file(self):
        default = "haptic-session-" + datetime.now().strftime("%Y%m%d-%H%M%S") + ".csv"
        path = filedialog.asksaveasfilename(
            title="Choose CSV log file", initialfile=default, defaultextension=".csv",
            filetypes=[("CSV files", "*.csv")], confirmoverwrite=False)
        if not path:
            self.csv_var.set(False)
            return
        try:
            import os
            is_new = not os.path.exists(path) or os.path.getsize(path) == 0
            self.csv_file = open(path, "a", encoding="utf-8", newline="")
            if is_new:
                self.csv_file.write("timestamp,top,right,bottom,left,source\n")
                self.csv_file.flush()
            self.log(f"CSV logging enabled: {os.path.abspath(path)}")
        except OSError as e:
            self.log(f"Could not open CSV file: {e}")
            self.csv_var.set(False)

    def write_csv_row(self, top, right, bottom, left, source):
        if not self.csv_var.get() or self.csv_file is None:
            return
        try:
            ts = datetime.now().strftime("%Y-%m-%d %H:%M:%S.%f")[:-3]
            self.csv_file.write(f"{ts},{top},{right},{bottom},{left},{source}\n")
            self.csv_file.flush()
        except OSError as e:
            self.log(f"CSV write failed: {e}")

    def close_csv(self):
        if self.csv_file is not None:
            try:
                self.csv_file.close()
                self.log("CSV logging stopped.")
            except OSError:
                pass
        self.csv_file = None

    # -------------------------------------------------------------- Shutdown
    def shutdown(self):
        self._cancel_jobs()
        self.apply_and_send(0, 0, 0, 0, "shutdown")
        if self.server:
            self.server.stop()
        self.serial.disconnect()
        self.close_csv()
        self.root.destroy()


def main():
    root = tk.Tk()
    MainApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()
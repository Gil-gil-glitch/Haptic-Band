"""A single motor's live PWM readout: name, fill bar, numeric value."""

import tkinter as tk

BAR_W, BAR_H = 100, 20
BG = "#282b30"
TRACK = "#1e2124"
ACTIVE = "#7289da"


class MotorGauge(tk.Frame):
    def __init__(self, parent, motor_name: str):
        super().__init__(parent, bg=BG)
        tk.Label(self, text=motor_name, bg=BG, fg="#b9bbbe", font=("Segoe UI", 9, "bold")).pack()
        self._canvas = tk.Canvas(
            self,
            width=BAR_W,
            height=BAR_H,
            bg=TRACK,
            highlightthickness=1,
            highlightbackground="#4f545c",
        )
        self._canvas.pack(pady=4)
        self._fill = self._canvas.create_rectangle(0, 0, 0, BAR_H, fill=ACTIVE, width=0)
        self._value = tk.Label(self, text="0 (0%)", bg=BG, fg="#ffffff")
        self._value.pack()

    def set_pwm(self, pwm: int):
        clamped = max(0, min(255, int(pwm)))
        fraction = clamped / 255.0
        self._canvas.coords(self._fill, 0, 0, fraction * BAR_W, BAR_H)
        self._value.config(text=f"{clamped} ({round(fraction * 100)}%)")

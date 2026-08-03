"""三轴状态面板：电压/电流/速度/坐标/状态标志。"""

from __future__ import annotations

from PyQt5.QtCore import Qt, pyqtSlot
from PyQt5.QtGui import QColor, QPalette
from PyQt5.QtWidgets import (
    QGridLayout,
    QGroupBox,
    QLabel,
    QVBoxLayout,
    QWidget,
)

from app.axis import AxisSnapshot
from app.config import AXES, AppConfig


class _Led(QLabel):
    """状态灯。"""

    def __init__(self, text: str) -> None:
        super().__init__(text)
        self.set_off()

    def set_on(self, color: str = "#3ad06c") -> None:
        self.setStyleSheet(f"background:{color};color:#111;padding:2px 6px;border-radius:3px;")

    def set_off(self) -> None:
        self.setStyleSheet("background:#333;color:#888;padding:2px 6px;border-radius:3px;")


class _AxisCard(QWidget):
    def __init__(self, axis: str, config: AppConfig) -> None:
        super().__init__()
        self.axis = axis
        self.config = config
        self.title = QLabel(f"<h3>{axis.upper()} 轴</h3>")
        self.title.setAlignment(Qt.AlignCenter)

        self.pos = QLabel("— mm")
        self.pos.setAlignment(Qt.AlignCenter)
        self.pos.setStyleSheet("font-size:20pt;font-weight:bold;")

        self.target = QLabel("目标 —")
        self.speed = QLabel("速度 — RPM")
        self.voltage = QLabel("母线电压 — V")
        self.current = QLabel("相电流 — A")

        self.led_en = _Led("使能")
        self.led_inpos = _Led("到位")
        self.led_stall = _Led("失步")
        self.led_prot = _Led("保护")
        self.led_home = _Led("回零中")

        info = QGridLayout()
        info.addWidget(self.target, 0, 0)
        info.addWidget(self.speed, 0, 1)
        info.addWidget(self.voltage, 1, 0)
        info.addWidget(self.current, 1, 1)

        leds = QGridLayout()
        leds.addWidget(self.led_en, 0, 0)
        leds.addWidget(self.led_inpos, 0, 1)
        leds.addWidget(self.led_stall, 0, 2)
        leds.addWidget(self.led_prot, 1, 0)
        leds.addWidget(self.led_home, 1, 1)

        lay = QVBoxLayout(self)
        lay.setContentsMargins(8, 8, 8, 8)
        lay.addWidget(self.title)
        lay.addWidget(self.pos)
        lay.addLayout(info)
        lay.addLayout(leds)

    def refresh(self, snap: AxisSnapshot) -> None:
        cal = self.config.axes[self.axis].calibration
        if snap.online:
            mm = snap.mm(cal) if cal.valid else None
            self.pos.setText(f"{mm:.2f} mm" if mm is not None else "未标定")
            self.target.setText(f"目标 {snap.target_mm(cal):.2f} mm" if cal.valid else "目标 —")
            self.speed.setText(f"速度 {snap.speed_rpm:.1f} RPM")
            self.voltage.setText(f"母线电压 {snap.bus_voltage:.2f} V")
            self.current.setText(f"相电流 {snap.phase_current:.3f} A")
            if snap.enabled:
                self.led_en.set_on("#3ad06c")
            else:
                self.led_en.set_off()
            if snap.in_position:
                self.led_inpos.set_on("#3ad06c")
            else:
                self.led_inpos.set_off()
            if snap.stalled:
                self.led_stall.set_on("#ff4040")
            else:
                self.led_stall.set_off()
            if snap.stall_protect:
                self.led_prot.set_on("#ff4040")
            else:
                self.led_prot.set_off()
            if snap.homing:
                self.led_home.set_on("#d0a020")
            else:
                self.led_home.set_off()
        else:
            self.pos.setText("离线")
            self.target.setText("目标 —")
            self.speed.setText("速度 — RPM")
            self.voltage.setText("母线电压 — V")
            self.current.setText("相电流 — A")
            for ld in (self.led_en, self.led_inpos, self.led_stall, self.led_prot, self.led_home):
                ld.set_off()


class StatusPanel(QWidget):
    def __init__(self, config: AppConfig) -> None:
        super().__init__()
        self.cards: dict[str, _AxisCard] = {a: _AxisCard(a, config) for a in AXES}

        grid = QGridLayout()
        for i, a in enumerate(AXES):
            grid.addWidget(self.cards[a], 0, i)
        lay = QVBoxLayout(self)
        lay.setContentsMargins(0, 0, 0, 0)
        lay.addLayout(grid)
        lay.addStretch(1)

    @pyqtSlot(object)
    def update_status(self, snaps: dict[str, AxisSnapshot]) -> None:
        for a in AXES:
            self.cards[a].refresh(snaps.get(a) or AxisSnapshot(axis=a, online=False))

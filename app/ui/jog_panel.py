"""点动 Jog 面板：按住方向键连续转动，松开即停。"""

from __future__ import annotations

from PyQt5.QtCore import Qt, pyqtSignal
from PyQt5.QtWidgets import (
    QGridLayout,
    QGroupBox,
    QHBoxLayout,
    QLabel,
    QPushButton,
    QSpinBox,
    QVBoxLayout,
    QWidget,
)

from app.backend import Backend
from app.config import AXES, AppConfig


class _JogButton(QPushButton):
    """按住即动、松开即停的方向键。"""

    def __init__(self, text: str, axis: str, cw: bool) -> None:
        super().__init__(text)
        self.axis = axis
        self.cw = cw
        self.setAutoRepeat(False)
        self.setMinimumSize(56, 44)
        self.setCheckable(False)


class JogPanel(QWidget):
    """六键点动 + 速度/加速度 + 急停。"""

    def __init__(self, config: AppConfig, backend: Backend) -> None:
        super().__init__()
        self.config = config
        self.backend = backend

        self.jog_speed = QSpinBox()
        self.jog_speed.setRange(1, 3000)
        self.jog_speed.setSuffix(" RPM")
        self.jog_speed.setValue(config.axes["x"].jog_speed)
        self.jog_accel = QSpinBox()
        self.jog_accel.setRange(0, 255)
        self.jog_accel.setValue(config.axes["x"].jog_accel)

        # 构造六键（布局：左为负向，右为正向）
        self.buttons: dict[tuple[str, bool], _JogButton] = {}
        grid = QGridLayout()
        grid.setHorizontalSpacing(6)
        grid.setVerticalSpacing(6)
        order = [
            ("x", "X"), ("y", "Y"), ("z", "Z"),
        ]
        for col, (a, label) in enumerate(order):
            head = QLabel(f"<b>{label} 轴</b>")
            head.setAlignment(Qt.AlignCenter)
            grid.addWidget(head, 0, col * 2, 1, 2)
            b_neg = _JogButton(f"{label} −", a, False)
            b_pos = _JogButton(f"{label} +", a, True)
            self.buttons[(a, False)] = b_neg
            self.buttons[(a, True)] = b_pos
            grid.addWidget(b_neg, 1, col * 2)
            grid.addWidget(b_pos, 1, col * 2 + 1)
            b_neg.pressed.connect(lambda a=a: self._start(a, False))
            b_neg.released.connect(lambda a=a: self._stop(a))
            b_pos.pressed.connect(lambda a=a: self._start(a, True))
            b_pos.released.connect(lambda a=a: self._stop(a))

        # 急停（大红）
        self.estop_btn = QPushButton("急 停 全 部")
        self.estop_btn.setObjectName("estopBtn")
        self.estop_btn.setMinimumHeight(48)
        self.estop_btn.clicked.connect(lambda: backend.estop(None))

        # 参数行
        from PyQt5.QtWidgets import QFormLayout

        form = QFormLayout()
        form.addRow("点动速度", self.jog_speed)
        form.addRow("加速度", self.jog_accel)
        self.jog_speed.valueChanged.connect(self._on_motion_changed)
        self.jog_accel.valueChanged.connect(self._on_motion_changed)

        box = QGroupBox("点动控制")
        bl = QVBoxLayout(box)
        bl.addLayout(grid)
        bl.addSpacing(6)
        bl.addLayout(form)
        bl.addSpacing(6)
        bl.addWidget(self.estop_btn)

        lay = QVBoxLayout(self)
        lay.setContentsMargins(0, 0, 0, 0)
        lay.addWidget(box)
        lay.addStretch(1)

        # 初始：未连接，按钮禁用
        self._set_connected(False, {a: False for a in AXES})

    def _on_motion_changed(self) -> None:
        spd = int(self.jog_speed.value())
        acc = int(self.jog_accel.value())
        for a in AXES:
            self.config.axes[a].jog_speed = spd
            self.config.axes[a].jog_accel = acc
            self.backend.update_motion(a, {"jog_speed": spd, "jog_accel": acc})

    def _start(self, axis: str, cw: bool) -> None:
        spd = int(self.jog_speed.value())
        self.backend.jog(axis, spd, cw)

    def _stop(self, axis: str) -> None:
        self.backend.stop_jog(axis)

    def _set_connected(self, connected: bool, online: dict[str, bool]) -> None:
        for (a, _cw), btn in self.buttons.items():
            btn.setEnabled(connected and online.get(a, False))
        self.estop_btn.setEnabled(connected)

    def set_online(self, connected: bool, online: dict[str, bool]) -> None:
        self._set_connected(connected, online)

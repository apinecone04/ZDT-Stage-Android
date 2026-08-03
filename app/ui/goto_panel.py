"""绝对定位 Goto 面板。"""

from __future__ import annotations

from PyQt5.QtCore import Qt, pyqtSignal, pyqtSlot
from PyQt5.QtWidgets import (
    QDoubleSpinBox,
    QFormLayout,
    QGridLayout,
    QGroupBox,
    QHBoxLayout,
    QLabel,
    QMessageBox,
    QPushButton,
    QSpinBox,
    QVBoxLayout,
    QWidget,
)

from app.axis import AxisSnapshot
from app.backend import Backend
from app.config import AXES, AppConfig


class GotoPanel(QWidget):
    def __init__(self, config: AppConfig, backend: Backend) -> None:
        super().__init__()
        self.config = config
        self.backend = backend
        self._snaps: dict[str, AxisSnapshot] = {a: AxisSnapshot(axis=a, online=False) for a in AXES}

        # 目标输入（按行程限上下限）
        self.targets: dict[str, QDoubleSpinBox] = {}
        for a in AXES:
            sb = QDoubleSpinBox()
            sb.setSuffix(" mm")
            sb.setDecimals(2)
            sb.setRange(0.0, float(config.axes[a].travel_mm))
            sb.setSingleStep(1.0)
            self.targets[a] = sb

        # 当前坐标显示
        self.current_lbls: dict[str, QLabel] = {a: QLabel("—") for a in AXES}
        self.calib_state: dict[str, QLabel] = {a: QLabel("未标定") for a in AXES}
        # 单轴 Goto 按钮
        self.axis_goto_btns: dict[str, QPushButton] = {}

        # 运动参数
        self.move_speed = QSpinBox()
        self.move_speed.setRange(1, 3000)
        self.move_speed.setSuffix(" RPM")
        self.move_speed.setValue(config.axes["x"].move_speed)
        self.move_accel = QSpinBox()
        self.move_accel.setRange(0, 255)
        self.move_accel.setValue(config.axes["x"].move_accel)
        self.move_speed.valueChanged.connect(self._on_motion)
        self.move_accel.valueChanged.connect(self._on_motion)

        grid = QGridLayout()
        grid.setHorizontalSpacing(8)
        grid.addWidget(QLabel("<b>轴</b>"), 0, 0)
        grid.addWidget(QLabel("<b>目标 (mm)</b>"), 0, 1)
        grid.addWidget(QLabel("<b>当前 (mm)</b>"), 0, 2)
        grid.addWidget(QLabel("<b>标定</b>"), 0, 3)
        grid.addWidget(QLabel("<b>单轴移动</b>"), 0, 4)
        for i, a in enumerate(AXES):
            grid.addWidget(QLabel(a.upper()), i + 1, 0)
            grid.addWidget(self.targets[a], i + 1, 1)
            self.current_lbls[a].setAlignment(Qt.AlignCenter)
            grid.addWidget(self.current_lbls[a], i + 1, 2)
            self.calib_state[a].setAlignment(Qt.AlignCenter)
            grid.addWidget(self.calib_state[a], i + 1, 3)
            ab = QPushButton("Goto " + a.upper())
            ab.clicked.connect(lambda _=False, a=a: self._goto_axis(a))
            self.axis_goto_btns[a] = ab
            grid.addWidget(ab, i + 1, 4)

        self.goto_btn = QPushButton("Goto  (三轴并行)")
        self.goto_btn.setMinimumHeight(40)
        self.goto_btn.setObjectName("gotoBtn")
        self.goto_btn.clicked.connect(self._goto)
        self.origin_btn = QPushButton("回到原点 (0,0,0)")
        self.origin_btn.clicked.connect(self._goto_origin)

        bh = QHBoxLayout()
        bh.addWidget(self.goto_btn)
        bh.addWidget(self.origin_btn)

        form = QFormLayout()
        form.addRow("定位速度", self.move_speed)
        form.addRow("定位加速度", self.move_accel)

        box = QGroupBox("绝对定位")
        bl = QVBoxLayout(box)
        bl.addLayout(grid)
        bl.addSpacing(6)
        bl.addLayout(form)
        bl.addSpacing(6)
        bl.addLayout(bh)

        lay = QVBoxLayout(self)
        lay.setContentsMargins(0, 0, 0, 0)
        lay.addWidget(box)
        lay.addStretch(1)

        self._update_calib_labels()

    def _on_motion(self) -> None:
        spd = int(self.move_speed.value())
        acc = int(self.move_accel.value())
        for a in AXES:
            self.config.axes[a].move_speed = spd
            self.config.axes[a].move_accel = acc
            self.backend.update_motion(a, {"move_speed": spd, "move_accel": acc})

    def _goto_axis(self, axis: str) -> None:
        cal = self.config.axes[axis].calibration
        if not cal.valid:
            QMessageBox.warning(self, "未标定", f"{axis.upper()} 轴未标定，无法定位。请先在“标定”页标定。")
            return
        target = float(self.targets[axis].value())
        target = max(0.0, min(float(self.config.axes[axis].travel_mm), target))
        self.backend.goto_mm(axis, target)

    def _goto(self) -> None:
        for a in AXES:
            cal = self.config.axes[a].calibration
            if not cal.valid:
                QMessageBox.warning(self, "未标定", f"{a.upper()} 轴未标定，无法定位。请先在“标定”页标定。")
                return
        for a in AXES:
            target = float(self.targets[a].value())
            # 软限位（SpinBox 已限，二次保险）
            target = max(0.0, min(float(self.config.axes[a].travel_mm), target))
            self.backend.goto_mm(a, target)

    def _goto_origin(self) -> None:
        for a in AXES:
            self.targets[a].setValue(0.0)
            self.backend.goto_mm(a, 0.0)

    @pyqtSlot(object)
    def update_status(self, snaps: dict[str, AxisSnapshot]) -> None:
        self._snaps = snaps
        for a in AXES:
            s = snaps.get(a)
            cal = self.config.axes[a].calibration
            if s and s.online and cal.valid and cal.angle_per_mm:
                self.current_lbls[a].setText(f"{s.mm(cal):.2f}")
                self.axis_goto_btns[a].setEnabled(True)
            elif s and s.online:
                self.current_lbls[a].setText("未标定")
                self.axis_goto_btns[a].setEnabled(False)
            else:
                self.current_lbls[a].setText("—")
                self.axis_goto_btns[a].setEnabled(False)

    def _update_calib_labels(self) -> None:
        for a in AXES:
            cal = self.config.axes[a].calibration
            if cal.valid:
                self.calib_state[a].setText(
                    f"<font color='#3ad06c'>已标定</font><br>"
                    f"<small>{cal.pulses_per_mm:.1f} p/mm</small>"
                )
            else:
                self.calib_state[a].setText("<font color='#d06c3a'>未标定</font>")

    def refresh_calibration(self) -> None:
        """标定完成后由主窗口调用，刷新标定状态标签。"""
        self._update_calib_labels()


"""单轴标定面板。

流程：记录起始角度 a0 → 发相对移动 N 脉冲 → 等到位 → 读终态角度 a1 →
用户输入实测物理位移 d_mm → 计算 pulses_per_mm=N/d_mm、angle_per_mm=(a1-a0)/d_mm。
"""

from __future__ import annotations

from PyQt5.QtCore import Qt, pyqtSignal, pyqtSlot, QTimer
from PyQt5.QtWidgets import (
    QComboBox,
    QDoubleSpinBox,
    QFormLayout,
    QGroupBox,
    QHBoxLayout,
    QLabel,
    QMessageBox,
    QPushButton,
    QSpinBox,
    QTextEdit,
    QVBoxLayout,
    QWidget,
)

from app.axis import AxisSnapshot
from app.backend import Backend
from app.config import AXES, AppConfig, Calibration

STATE_IDLE = "idle"
STATE_MOVING = "moving"
STATE_AWAIT = "await_measurement"


class CalibratePanel(QWidget):
    def __init__(self, config: AppConfig, backend: Backend) -> None:
        super().__init__()
        self.config = config
        self.backend = backend
        self._snaps: dict[str, AxisSnapshot] = {a: AxisSnapshot(axis=a, online=False) for a in AXES}

        self.state = STATE_IDLE
        self.cur_axis = "x"
        self.a0 = 0
        self.a1 = 0
        self.move_n = 5000
        self._move_timeout_ms = 0

        self.axis_box = QComboBox()
        for a in AXES:
            self.axis_box.addItem(a.upper(), a)
        self.axis_box.currentIndexChanged.connect(self._on_axis_changed)

        self.pulse_sp = QSpinBox()
        self.pulse_sp.setRange(1, 2_000_000_000)
        self.pulse_sp.setValue(1000)
        self.pulse_sp.setSuffix(" 脉冲")
        self.pulse_sp.setToolTip(
            "标定用相对移动脉冲数。建议先用较小值（如 1000），\n"
            "在行程中部、移动方向有富余时标定，避免撞限位堵转。"
        )

        self.measured_sp = QDoubleSpinBox()
        self.measured_sp.setRange(-100000.0, 100000.0)
        self.measured_sp.setDecimals(3)
        self.measured_sp.setSuffix(" mm")
        self.measured_sp.setValue(0.0)
        self.measured_sp.setToolTip(
            "带符号实测位移：正=沿该轴 +mm 方向，负=反方向。\n"
            "Z 轴 +mm = 向下（远离顶部原点），向下为正、向上为负。"
        )

        self.read_btn = QPushButton("读取当前角度")
        self.read_btn.clicked.connect(self._read_current)
        self.start_btn = QPushButton("① 开始（发移动并记录）")
        self.start_btn.setObjectName("gotoBtn")
        self.start_btn.clicked.connect(self._start)
        self.finish_btn = QPushButton("② 输入实测后完成标定")
        self.finish_btn.setEnabled(False)
        self.finish_btn.clicked.connect(self._finish)

        self.a0_lbl = QLabel("—")
        self.a1_lbl = QLabel("—")
        self.result_lbl = QLabel("未标定")

        form = QFormLayout()
        form.addRow("标定轴", self.axis_box)
        form.addRow("移动脉冲 N", self.pulse_sp)
        form.addRow("起始角度 a0", self.a0_lbl)
        form.addRow("终态角度 a1", self.a1_lbl)
        form.addRow("实测位移 d (mm)", self.measured_sp)

        bh = QHBoxLayout()
        bh.addWidget(self.read_btn)
        bh.addWidget(self.start_btn)
        bh.addWidget(self.finish_btn)

        self.log = QTextEdit()
        self.log.setReadOnly(True)
        self.log.setMaximumHeight(120)

        box = QGroupBox("标定")
        bl = QVBoxLayout(box)
        bl.addLayout(form)
        bl.addLayout(bh)
        bl.addWidget(self.result_lbl)
        bl.addWidget(self.log)

        lay = QVBoxLayout(self)
        lay.setContentsMargins(0, 0, 0, 0)
        lay.addWidget(box)
        lay.addStretch(1)

        # 信号
        backend.command_result.connect(self._on_cmd_result)

        # 监视到位
        self._watch = QTimer(self)
        self._watch.setInterval(200)
        self._watch.timeout.connect(self._tick)
        self._watch.start()

        self._refresh_state_label()

    def _on_axis_changed(self) -> None:
        self.cur_axis = self.axis_box.currentData()
        self._refresh_state_label()

    def _refresh_state_label(self) -> None:
        cal = self.config.axes[self.cur_axis].calibration
        if cal.valid:
            self.result_lbl.setText(
                f"<font color='#3ad06c'>{self.cur_axis.upper()} 已标定</font>："
                f"pulses/mm={cal.pulses_per_mm:.3f}，angle/mm={cal.angle_per_mm:.3f}"
            )
        else:
            self.result_lbl.setText(f"<font color='#d06c3a'>{self.cur_axis.upper()} 未标定</font>")

    def _log(self, msg: str) -> None:
        self.log.append(f"· {msg}")

    def _read_current(self) -> None:
        """从最新轮询快照取当前角度（用 sys_status，不用 0x36）。"""
        s = self._snaps.get(self.cur_axis)
        if s and s.online:
            self.a0_lbl.setText(f"{s.angle_position}")
            self._log(f"{self.cur_axis.upper()} 当前角度 = {s.angle_position}")
        else:
            self.a0_lbl.setText("—（离线）")

    @pyqtSlot(str, bool, str)
    def _on_cmd_result(self, axis: str, ok: bool, msg: str) -> None:
        if axis != self.cur_axis:
            return
        self._log(f"{axis.upper()} {msg} {'OK' if ok else '失败'}")

    def _start(self) -> None:
        s = self._snaps.get(self.cur_axis)
        if not s or not s.online:
            QMessageBox.warning(self, "离线", f"{self.cur_axis.upper()} 轴离线，请先连接。")
            return
        self.move_n = int(self.pulse_sp.value())
        # a0 直接取自最新 sys_status 快照（0x36 在 X42S-2 上不可靠，已弃用）
        self.a0 = s.angle_position
        self.a0_lbl.setText(f"{self.a0}")
        self._log(f"{self.cur_axis.upper()} 起始角度 a0 = {self.a0}（取自 sys_status）")
        # 发相对移动
        self.backend.calibrate_move(self.cur_axis, self.move_n)
        self.state = STATE_MOVING
        self._move_timeout_ms = 0
        self.finish_btn.setEnabled(False)
        self.start_btn.setEnabled(False)
        self._log(f"发出相对移动 {self.move_n} 脉冲，等待到位…")

    def _tick(self) -> None:
        if self.state == STATE_MOVING:
            s = self._snaps.get(self.cur_axis)
            self._move_timeout_ms += 200
            # 堵转/撞限位：立即中止
            if s and (s.stalled or s.stall_protect):
                self.state = STATE_IDLE
                self.start_btn.setEnabled(True)
                self._log("检测到堵转/撞限位，标定中止！请减小脉冲数，或把轴移到行程中部、"
                          "确保移动方向两侧都有富余后再标定。")
                return
            if s and s.in_position:
                # a1 取自到位时的同一帧快照（已含最终位置）
                self.a1 = s.angle_position
                self.a1_lbl.setText(f"{self.a1}")
                self.state = STATE_AWAIT
                self.finish_btn.setEnabled(True)
                self._log(f"已到位，a1 = {self.a1}（Δ角度 = {self.a1 - self.a0}）。"
                          f"请量测物理位移并输入 d(mm)，再点②完成。")
            elif self._move_timeout_ms > 60000:
                self.state = STATE_IDLE
                self.start_btn.setEnabled(True)
                self._log("超时：60s 未到位，标定中止。")

    def _finish(self) -> None:
        d = float(self.measured_sp.value())
        if d == 0:
            QMessageBox.warning(self, "位移为零", "实测位移不能为 0。")
            return
        pulses_per_mm = self.move_n / d
        angle_per_mm = (self.a1 - self.a0) / d
        if angle_per_mm == 0:
            QMessageBox.warning(self, "角度无变化", "角度变化为 0，标定失败。")
            return
        cal = Calibration(
            pulses_per_mm=pulses_per_mm,
            angle_per_mm=angle_per_mm,
            origin_angle=self.a0,  # 以标定起点为原点 → 此刻显示 0
            valid=True,
        )
        self.backend.apply_calibration(self.cur_axis, cal)
        # 同步本地 config 以便即时刷新
        self.config.axes[self.cur_axis].calibration = cal
        self._log(
            f"标定完成：pulses/mm={pulses_per_mm:.3f}，angle/mm={angle_per_mm:.3f}"
        )
        self.state = STATE_IDLE
        self.start_btn.setEnabled(True)
        self.finish_btn.setEnabled(False)
        self._refresh_state_label()
        self.calibration_applied.emit(self.cur_axis)

    calibration_applied = pyqtSignal(str)

    @pyqtSlot(object)
    def update_status(self, snaps: dict[str, AxisSnapshot]) -> None:
        self._snaps = snaps

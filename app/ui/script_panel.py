# -*- coding: utf-8 -*-
"""脚本/路径运行面板：按设定路径依次定位，可重复运行。

状态机由轮询状态更新驱动（无需阻塞后台线程）：
  move -> 等三轴 in_position -> dwell(停留) -> 下一点 -> ... -> 循环。
"""

from __future__ import annotations

import time
from typing import Any

from PyQt5.QtCore import Qt, pyqtSlot, QTimer
from PyQt5.QtWidgets import (
    QAbstractItemView,
    QDoubleSpinBox,
    QFormLayout,
    QGridLayout,
    QGroupBox,
    QHBoxLayout,
    QHeaderView,
    QLabel,
    QMessageBox,
    QPushButton,
    QSpinBox,
    QTableWidget,
    QTableWidgetItem,
    QVBoxLayout,
    QWidget,
)

from app.axis import AxisSnapshot
from app.backend import Backend
from app.config import AXES, AppConfig, ScriptPoint


class ScriptPanel(QWidget):
    def __init__(self, config: AppConfig, backend: Backend) -> None:
        super().__init__()
        self.config = config
        self.backend = backend
        self._snaps: dict[str, AxisSnapshot] = {a: AxisSnapshot(axis=a, online=False) for a in AXES}

        # 运行状态
        self._running = False
        self._phase = "move"  # 'move' | 'dwell'
        self._step = 0
        self._loop = 0
        self._loops_target = 1
        self._dwell_until = 0.0
        self._points: list[ScriptPoint] = []

        # 路径表
        self.table = QTableWidget(0, 5)
        self.table.setHorizontalHeaderLabels(["#", "X (mm)", "Y (mm)", "Z (mm)", "停留 (s)"])
        self.table.horizontalHeader().setSectionResizeMode(QHeaderView.Stretch)
        self.table.setSelectionBehavior(QAbstractItemView.SelectRows)
        self.table.setEditTriggers(QTableWidget.DoubleClicked | QTableWidget.EditKeyPressed)

        self.add_cur_btn = QPushButton("添加当前点")
        self.add_row_btn = QPushButton("新增空行")
        self.del_btn = QPushButton("删除选中")
        self.up_btn = QPushButton("上移")
        self.down_btn = QPushButton("下移")
        self.clear_btn = QPushButton("清空")
        self.add_cur_btn.clicked.connect(self._add_current)
        self.add_row_btn.clicked.connect(lambda: self._append(ScriptPoint()))
        self.del_btn.clicked.connect(self._delete_selected)
        self.up_btn.clicked.connect(lambda: self._move(-1))
        self.down_btn.clicked.connect(lambda: self._move(1))
        self.clear_btn.clicked.connect(self._clear)

        edit_row = QHBoxLayout()
        for b in (self.add_cur_btn, self.add_row_btn, self.del_btn, self.up_btn, self.down_btn, self.clear_btn):
            edit_row.addWidget(b)

        # 运行控制
        self.loops_sp = QSpinBox()
        self.loops_sp.setRange(0, 100000)
        self.loops_sp.setValue(1)
        self.loops_sp.setToolTip("0 = 无限循环，直到点停止")
        self.loops_sp.setSpecialValueText("无限")
        self.speed_sp = QSpinBox()
        self.speed_sp.setRange(0, 3000)
        self.speed_sp.setValue(0)
        self.speed_sp.setSuffix(" RPM")
        self.speed_sp.setSpecialValueText("用当前")
        self.speed_sp.setToolTip("0 = 用各轴当前定位速度；>0 运行前覆盖三轴定位速度")
        self.accel_sp = QSpinBox()
        self.accel_sp.setRange(0, 255)
        self.accel_sp.setValue(0)
        self.accel_sp.setSpecialValueText("用当前")

        self.run_btn = QPushButton("▶ 运行")
        self.run_btn.setObjectName("gotoBtn")
        self.run_btn.setMinimumHeight(38)
        self.run_btn.clicked.connect(self._run)
        self.stop_btn = QPushButton("■ 停止")
        self.stop_btn.setObjectName("estopBtn")
        self.stop_btn.setMinimumHeight(38)
        self.stop_btn.setEnabled(False)
        self.stop_btn.clicked.connect(self._stop)

        self.progress = QLabel("待运行")
        self.progress.setAlignment(Qt.AlignCenter)
        self.progress.setStyleSheet("font-weight:bold;color:#ffe066;padding:4px;")

        form = QFormLayout()
        form.addRow("循环次数", self.loops_sp)
        form.addRow("定位速度", self.speed_sp)
        form.addRow("定位加速度", self.accel_sp)

        run_row = QHBoxLayout()
        run_row.addWidget(self.run_btn, 1)
        run_row.addWidget(self.stop_btn, 1)

        box = QGroupBox("路径运行")
        bl = QVBoxLayout(box)
        bl.addLayout(edit_row)
        bl.addWidget(self.table)
        bl.addLayout(form)
        bl.addLayout(run_row)
        bl.addWidget(self.progress)

        lay = QVBoxLayout(self)
        lay.setContentsMargins(0, 0, 0, 0)
        lay.addWidget(box)
        lay.addStretch(1)

        self._load_table()

        # 监视到位/停留
        self._watch = QTimer(self)
        self._watch.setInterval(100)
        self._watch.timeout.connect(self._tick)
        self._watch.start()

    # ---- 表格 ----
    def _load_table(self) -> None:
        self.table.setRowCount(0)
        for p in self.config.script:
            self._append_row(p)

    def _append_row(self, p: ScriptPoint) -> None:
        r = self.table.rowCount()
        self.table.insertRow(r)
        self.table.setItem(r, 0, QTableWidgetItem(str(r + 1)))
        self.table.setItem(r, 1, QTableWidgetItem(f"{p.x:.2f}"))
        self.table.setItem(r, 2, QTableWidgetItem(f"{p.y:.2f}"))
        self.table.setItem(r, 3, QTableWidgetItem(f"{p.z:.2f}"))
        self.table.setItem(r, 4, QTableWidgetItem(f"{p.dwell:.2f}"))

    def _collect(self) -> list[ScriptPoint]:
        out: list[ScriptPoint] = []
        for r in range(self.table.rowCount()):
            try:
                out.append(ScriptPoint(
                    x=float(self.table.item(r, 1).text()),
                    y=float(self.table.item(r, 2).text()),
                    z=float(self.table.item(r, 3).text()),
                    dwell=float(self.table.item(r, 4).text()),
                ))
            except (ValueError, AttributeError):
                pass
        return out

    def _save(self) -> None:
        self.config.script = self._collect()
        self.config.save()

    def _append(self, p: ScriptPoint) -> None:
        self._append_row(p)
        self._save()

    def _current_mm(self) -> tuple[float, float, float] | None:
        out = []
        for a in AXES:
            s = self._snaps.get(a)
            cal = self.config.axes[a].calibration
            if s and s.online and cal.valid and cal.angle_per_mm:
                out.append(s.mm(cal))
            else:
                return None
        return (out[0], out[1], out[2])

    def _add_current(self) -> None:
        cur = self._current_mm()
        if cur is None:
            QMessageBox.warning(self, "无法获取", "当前坐标不可用（轴离线或未标定）。")
            return
        self._append(ScriptPoint(x=cur[0], y=cur[1], z=cur[2], dwell=0.0))

    def _delete_selected(self) -> None:
        r = self.table.currentRow()
        if r >= 0:
            self.table.removeRow(r)
            self._renumber()
            self._save()

    def _move(self, direction: int) -> None:
        r = self.table.currentRow()
        if r < 0:
            return
        r2 = r + direction
        if 0 <= r2 < self.table.rowCount():
            # 交换两行内容
            cols = [self.table.takeItem(r, c) for c in range(self.table.columnCount())]
            cols2 = [self.table.takeItem(r2, c) for c in range(self.table.columnCount())]
            for c, it in enumerate(cols2):
                self.table.setItem(r, c, it)
            for c, it in enumerate(cols):
                self.table.setItem(r2, c, it)
            self.table.selectRow(r2)
            self._renumber()
            self._save()

    def _clear(self) -> None:
        self.table.setRowCount(0)
        self._save()

    def _renumber(self) -> None:
        for r in range(self.table.rowCount()):
            self.table.item(r, 0).setText(str(r + 1))

    # ---- 运行 ----
    def _run(self) -> None:
        pts = self._collect()
        if not pts:
            QMessageBox.warning(self, "空路径", "请先添加路径点。")
            return
        uncalib = [a.upper() for a in AXES if not self.config.axes[a].calibration.valid]
        if uncalib:
            QMessageBox.warning(self, "未标定", f"{'、'.join(uncalib)} 轴未标定，无法运行脚本。")
            return
        offline = [a.upper() for a in AXES if not self._snaps.get(a) or not self._snaps[a].online]
        if offline:
            QMessageBox.warning(self, "离线", f"{'、'.join(offline)} 轴离线，无法运行。")
            return
        # 速度覆盖
        spd = int(self.speed_sp.value())
        acc = int(self.accel_sp.value())
        if spd > 0:
            for a in AXES:
                m = {"move_speed": spd}
                if acc > 0:
                    m["move_accel"] = acc
                self.backend.update_motion(a, m)
        self._points = pts
        self._loops_target = int(self.loops_sp.value())  # 0 = 无限
        self._loop = 0
        self._step = 0
        self._phase = "move"
        self._running = True
        self._set_run_ui(True)
        self._goto_step(0)

    def _goto_step(self, idx: int) -> None:
        p = self._points[idx]
        self._phase = "move"
        self.backend.goto_mm("x", p.x)
        self.backend.goto_mm("y", p.y)
        self.backend.goto_mm("z", p.z)
        self._update_progress()

    def _stop(self) -> None:
        if not self._running:
            return
        self._running = False
        self._phase = "move"
        self.backend.estop(None)
        self._set_run_ui(False)
        self.progress.setText("已停止")

    def _set_run_ui(self, running: bool) -> None:
        self.run_btn.setEnabled(not running)
        self.stop_btn.setEnabled(running)
        # 运行时禁用编辑
        for w in (self.add_cur_btn, self.add_row_btn, self.del_btn, self.up_btn,
                  self.down_btn, self.clear_btn, self.loops_sp, self.speed_sp, self.accel_sp):
            w.setEnabled(not running)
        self.table.setEditTriggers(QTableWidget.NoEditTriggers if running
                                   else (QTableWidget.DoubleClicked | QTableWidget.EditKeyPressed))

    def _update_progress(self) -> None:
        n = len(self._points)
        lt = "∞" if self._loops_target == 0 else str(self._loops_target)
        ph = "移动中" if self._phase == "move" else "停留"
        self.progress.setText(
            f"第 {self._step + 1}/{n} 点 · 第 {self._loop + 1}/{lt} 次 · {ph}"
        )

    @pyqtSlot(object)
    def update_status(self, snaps: dict[str, AxisSnapshot]) -> None:
        self._snaps = snaps

    def _tick(self) -> None:
        if not self._running:
            return
        snaps = self._snaps
        # 任一轴堵转/离线 → 中止
        for a in AXES:
            s = snaps.get(a)
            if not s or not s.online:
                self._abort(f"{a.upper()} 轴离线，脚本中止")
                return
            if s.stalled or s.stall_protect:
                self._abort(f"{a.upper()} 轴堵转/保护，脚本中止")
                return

        if self._phase == "move":
            # 三轴均到位 → 进入停留
            if all(snaps[a].in_position for a in AXES):
                dwell = self._points[self._step].dwell
                if dwell > 0:
                    self._phase = "dwell"
                    self._dwell_until = time.monotonic() + dwell
                    self._update_progress()
                else:
                    self._advance()
        elif self._phase == "dwell":
            if time.monotonic() >= self._dwell_until:
                self._advance()

    def _advance(self) -> None:
        n = len(self._points)
        self._step += 1
        if self._step >= n:
            self._loop += 1
            if self._loops_target != 0 and self._loop >= self._loops_target:
                # 完成
                self._running = False
                self._set_run_ui(False)
                self.progress.setText(f"完成 · 共 {self._loop} 次")
                return
            self._step = 0
        self._goto_step(self._step)

    def _abort(self, msg: str) -> None:
        self._running = False
        self._phase = "move"
        self._set_run_ui(False)
        self.progress.setText("已中止：" + msg)

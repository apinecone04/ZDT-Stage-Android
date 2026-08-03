"""回零 / 原点面板。

两种“零点”概念，务必区分：
- 电机零点 (驱动板内)：由 home（机械回零）或 set_home（把当前位置置零）写入驱动板固件，
  影响电机自身位置计数。回零完成会自动把显示原点同步到当前位置。
- 显示原点 (上位机内)：set_origin_here 仅在上位机 config 里记录 origin_angle 偏移，
  只影响 mm 读数，不改电机零点。
"""

from __future__ import annotations

from PyQt5.QtCore import Qt, pyqtSignal, pyqtSlot, QTimer
from PyQt5.QtWidgets import (
    QGridLayout,
    QGroupBox,
    QLabel,
    QMessageBox,
    QPushButton,
    QVBoxLayout,
    QWidget,
)

from app.axis import AxisSnapshot
from app.backend import Backend
from app.config import AXES, AppConfig


class HomePanel(QWidget):
    def __init__(self, config: AppConfig, backend: Backend) -> None:
        super().__init__()
        self.config = config
        self.backend = backend
        self._snaps: dict[str, AxisSnapshot] = {a: AxisSnapshot(axis=a, online=False) for a in AXES}
        self._homing_issued: dict[str, bool] = {a: False for a in AXES}

        self.home_btns: dict[str, QPushButton] = {}
        self.stop_btns: dict[str, QPushButton] = {}
        self.motor_zero_btns: dict[str, QPushButton] = {}
        self.origin_btns: dict[str, QPushButton] = {}
        self.state_lbls: dict[str, QLabel] = {}

        grid = QGridLayout()
        headers = ["轴", "回零\n(机械)", "停", "设电机零点\n(驱动板)", "设显示原点\n(上位机)", "状态"]
        for c, h in enumerate(headers):
            l = QLabel("<b>" + h.replace("\n", "<br>") + "</b>")
            l.setAlignment(Qt.AlignCenter)
            grid.addWidget(l, 0, c)
        for i, a in enumerate(AXES):
            hb = QPushButton("回零")
            sb = QPushButton("停")
            mb = QPushButton("电机零点")
            ob = QPushButton("显示原点")
            hb.setToolTip("驱动板执行机械回零（到限位/零点开关），回零完成自动设显示原点")
            mb.setToolTip("把当前位置作为电机零点写入驱动板固件（set_home），随后位置计数归零")
            ob.setToolTip("仅在上位机把当前位置设为坐标原点（显示归零，不改电机零点）")
            hb.clicked.connect(lambda _=False, a=a: self._home(a))
            sb.clicked.connect(lambda _=False, a=a: self._stop(a))
            mb.clicked.connect(lambda _=False, a=a: self._set_motor_zero(a))
            ob.clicked.connect(lambda _=False, a=a: self._set_origin(a))
            self.home_btns[a] = hb
            self.stop_btns[a] = sb
            self.motor_zero_btns[a] = mb
            self.origin_btns[a] = ob
            self.state_lbls[a] = QLabel("—")
            self.state_lbls[a].setAlignment(Qt.AlignCenter)
            grid.addWidget(QLabel(a.upper()), i + 1, 0)
            grid.addWidget(hb, i + 1, 1)
            grid.addWidget(sb, i + 1, 2)
            grid.addWidget(mb, i + 1, 3)
            grid.addWidget(ob, i + 1, 4)
            grid.addWidget(self.state_lbls[a], i + 1, 5)

        info = QLabel(
            "<small><b>回零</b>：电机自身机械回零，零点存于驱动板。<br>"
            "<b>设电机零点</b>：把当前位置写入驱动板作为零点（之后位置计数从此处起）。<br>"
            "<b>设显示原点</b>：仅上位机显示归零，不改电机。<br>"
            "<b>回到显示原点</b>：三轴定位到坐标 (0,0,0)mm（需已标定）。</small>"
        )
        info.setWordWrap(True)

        self.goto_origin_btn = QPushButton("回到显示原点 (0,0,0)")
        self.goto_origin_btn.setObjectName("gotoBtn")
        self.goto_origin_btn.setMinimumHeight(36)
        self.goto_origin_btn.setToolTip("三轴分别 Goto 到 0mm（显示原点）；需各轴已标定")
        self.goto_origin_btn.clicked.connect(self._goto_display_origin)

        box = QGroupBox("回零 / 原点")
        bl = QVBoxLayout(box)
        bl.addLayout(grid)
        bl.addWidget(info)
        bl.addWidget(self.goto_origin_btn)

        lay = QVBoxLayout(self)
        lay.setContentsMargins(0, 0, 0, 0)
        lay.addWidget(box)
        lay.addStretch(1)

        self._watch = QTimer(self)
        self._watch.setInterval(300)
        self._watch.timeout.connect(self._watch_homing)
        self._watch.start()

    def _home(self, axis: str) -> None:
        self._homing_issued[axis] = True
        self.backend.home(axis)

    def _stop(self, axis: str) -> None:
        self.backend.stop_home(axis)
        self._homing_issued[axis] = False

    def _set_motor_zero(self, axis: str) -> None:
        """驱动板级：set_home，把当前位置置零；随后显示原点同步。"""
        self.backend.set_home(axis)

    def _set_origin(self, axis: str) -> None:
        """上位机级：仅显示归零。"""
        self.backend.set_origin_here(axis)

    def _goto_display_origin(self) -> None:
        """三轴定位到显示原点 (0,0,0)mm。"""
        uncalib = [a.upper() for a in AXES if not self.config.axes[a].calibration.valid]
        if uncalib:
            QMessageBox.warning(
                self, "未标定",
                f"{'、'.join(uncalib)} 轴未标定，无法定位到显示原点。请先在“标定”页标定。",
            )
            return
        for a in AXES:
            self.backend.goto_mm(a, 0.0)

    @pyqtSlot(object)
    def update_status(self, snaps: dict[str, AxisSnapshot]) -> None:
        self._snaps = snaps

    def _watch_homing(self) -> None:
        for a in AXES:
            s = self._snaps.get(a)
            if s is None:
                continue
            if not s.online:
                txt = "<font color='#888'>离线</font>"
            elif s.homing_failed:
                txt = "<font color='#ff4040'>回零失败</font>"
                if self._homing_issued[a]:
                    self._homing_issued[a] = False
            elif s.homing:
                txt = "<font color='#d0a020'>回零中…</font>"
            else:
                if self._homing_issued[a]:
                    self._homing_issued[a] = False
                    self.backend.set_origin_here(a)
                    txt = "<font color='#3ad06c'>回零完成·已设原点</font>"
                else:
                    txt = "就绪"
            self.state_lbls[a].setText(txt)

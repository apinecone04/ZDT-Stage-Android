"""预设点位 / 示教面板。"""

from __future__ import annotations

from PyQt5.QtCore import Qt, pyqtSignal, pyqtSlot
from PyQt5.QtWidgets import (
    QFileDialog,
    QGroupBox,
    QHBoxLayout,
    QInputDialog,
    QLabel,
    QLineEdit,
    QPushButton,
    QTableWidget,
    QTableWidgetItem,
    QVBoxLayout,
    QWidget,
)

from app.axis import AxisSnapshot
from app.backend import Backend
from app.config import AXES, AppConfig, Preset


class TeachPanel(QWidget):
    def __init__(self, config: AppConfig, backend: Backend) -> None:
        super().__init__()
        self.config = config
        self.backend = backend
        self._snaps: dict[str, AxisSnapshot] = {a: AxisSnapshot(axis=a, online=False) for a in AXES}

        self.table = QTableWidget(0, 4)
        self.table.setHorizontalHeaderLabels(["名称", "X (mm)", "Y (mm)", "Z (mm)"])
        self.table.horizontalHeader().setSectionResizeMode(0, 3)
        self.table.horizontalHeader().setStretchLastSection(False)
        self.table.setEditTriggers(QTableWidget.DoubleClicked | QTableWidget.EditKeyPressed)

        self.add_btn = QPushButton("添加当前点")
        self.goto_btn = QPushButton("调出选中点 → Goto")
        self.upd_btn = QPushButton("用当前坐标更新选中")
        self.del_btn = QPushButton("删除选中")
        self.clear_btn = QPushButton("清空")
        self.add_btn.clicked.connect(self._add_current)
        self.goto_btn.clicked.connect(self._goto_selected)
        self.upd_btn.clicked.connect(self._update_selected)
        self.del_btn.clicked.connect(self._delete_selected)
        self.clear_btn.clicked.connect(self._clear)

        bh = QHBoxLayout()
        for b in (self.add_btn, self.goto_btn, self.upd_btn, self.del_btn, self.clear_btn):
            bh.addWidget(b)

        box = QGroupBox("预设点位")
        bl = QVBoxLayout(box)
        bl.addWidget(self.table)
        bl.addLayout(bh)

        lay = QVBoxLayout(self)
        lay.setContentsMargins(0, 0, 0, 0)
        lay.addWidget(box)
        lay.addStretch(1)

        self._load_table()

    def _load_table(self) -> None:
        self.table.setRowCount(0)
        for p in self.config.presets:
            self._append_row(p)

    def _append_row(self, p: Preset) -> None:
        r = self.table.rowCount()
        self.table.insertRow(r)
        self.table.setItem(r, 0, QTableWidgetItem(p.name))
        self.table.setItem(r, 1, QTableWidgetItem(f"{p.x:.2f}"))
        self.table.setItem(r, 2, QTableWidgetItem(f"{p.y:.2f}"))
        self.table.setItem(r, 3, QTableWidgetItem(f"{p.z:.2f}"))

    def _collect_presets(self) -> list[Preset]:
        out: list[Preset] = []
        for r in range(self.table.rowCount()):
            name = self.table.item(r, 0).text() if self.table.item(r, 0) else f"点{r+1}"
            try:
                x = float(self.table.item(r, 1).text())
                y = float(self.table.item(r, 2).text())
                z = float(self.table.item(r, 3).text())
            except (ValueError, AttributeError):
                continue
            out.append(Preset(name=name, x=x, y=y, z=z))
        return out

    def _save(self) -> None:
        self.config.presets = self._collect_presets()
        self.config.save()

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
            return
        name, ok = QInputDialog.getText(self, "命名", "点位名称:", text=f"点{self.table.rowCount()+1}")
        if not ok:
            return
        p = Preset(name=name or "未命名", x=cur[0], y=cur[1], z=cur[2])
        self._append_row(p)
        self._save()

    def _goto_selected(self) -> None:
        r = self.table.currentRow()
        if r < 0:
            return
        try:
            x = float(self.table.item(r, 1).text())
            y = float(self.table.item(r, 2).text())
            z = float(self.table.item(r, 3).text())
        except (ValueError, AttributeError):
            return
        self.backend.goto_mm("x", x)
        self.backend.goto_mm("y", y)
        self.backend.goto_mm("z", z)
        self._save()

    def _update_selected(self) -> None:
        r = self.table.currentRow()
        if r < 0:
            return
        cur = self._current_mm()
        if cur is None:
            return
        self.table.item(r, 1).setText(f"{cur[0]:.2f}")
        self.table.item(r, 2).setText(f"{cur[1]:.2f}")
        self.table.item(r, 3).setText(f"{cur[2]:.2f}")
        self._save()

    def _delete_selected(self) -> None:
        r = self.table.currentRow()
        if r >= 0:
            self.table.removeRow(r)
            self._save()

    def _clear(self) -> None:
        self.table.setRowCount(0)
        self._save()

    @pyqtSlot(object)
    def update_status(self, snaps: dict[str, AxisSnapshot]) -> None:
        self._snaps = snaps

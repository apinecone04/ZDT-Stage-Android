"""3D 空间视图。

优先用 ``pyqtgraph.opengl``（依赖 PyOpenGL）绘制行程线框、地板网格、末端点、
垂直吊线、地板投影、轨迹与显眼的坐标读数浮层；
无 OpenGL 时回退到三个 2D 投影视图（同样带坐标读数）。
"""

from __future__ import annotations

import numpy as np
from PyQt5.QtCore import Qt
from PyQt5.QtGui import QColor, QFont
from PyQt5.QtWidgets import QGridLayout, QGroupBox, QHBoxLayout, QLabel, QPushButton, QVBoxLayout, QWidget

import pyqtgraph as pg

try:  # 依赖 PyOpenGL
    import pyqtgraph.opengl as gl  # noqa: F401
    import OpenGL  # noqa: F401
    _HAS_OPENGL = True
except Exception:  # noqa: BLE001
    _HAS_OPENGL = False


def _box_edges(xmax: float, ymax: float, zmax: float) -> np.ndarray:
    """行程线框 12 条边，(24,3) 顶点（每 2 个一段）。"""
    p = [
        (0, 0, 0), (xmax, 0, 0), (xmax, 0, 0), (xmax, ymax, 0),
        (xmax, ymax, 0), (0, ymax, 0), (0, ymax, 0), (0, 0, 0),
        (0, 0, zmax), (xmax, 0, zmax), (xmax, 0, zmax), (xmax, ymax, zmax),
        (xmax, ymax, zmax), (0, ymax, zmax), (0, ymax, zmax), (0, 0, zmax),
        (0, 0, 0), (0, 0, zmax), (xmax, 0, 0), (xmax, 0, zmax),
        (xmax, ymax, 0), (xmax, ymax, zmax), (0, ymax, 0), (0, ymax, zmax),
    ]
    return np.array(p, dtype=np.float64)


class _CoordOverlay(QLabel):
    """左上角显眼坐标读数浮层。"""

    def __init__(self, travel: dict[str, float]) -> None:
        super().__init__()
        self._travel = travel
        self._online = False
        self.setStyleSheet(
            "background:rgba(0,0,0,160);color:#ffe066;padding:8px 14px;"
            "border-radius:8px;border:1px solid #555;"
        )
        f = QFont("Consolas", 14)
        f.setBold(True)
        self.setFont(f)
        self._refresh(0.0, 0.0, 0.0)

    def set_online(self, online: bool) -> None:
        self._online = online
        self._refresh(0.0, 0.0, 0.0)

    def _refresh(self, x: float, y: float, z: float) -> None:
        col = "#ffe066" if self._online else "#888888"
        tag = "在线" if self._online else "离线"
        self.setText(
            f"<span style='color:{col}'>XYZ 末端点 [{tag}]</span><br>"
            f"<span style='color:#ff7a4a'>X:</span> {x:7.2f} mm / {self._travel['x']:.0f}<br>"
            f"<span style='color:#5ad0ff'>Y:</span> {y:7.2f} mm / {self._travel['y']:.0f}<br>"
            f"<span style='color:#9aff5a'>Z:</span> {z:7.2f} mm / {self._travel['z']:.0f}"
        )


class View3DOpenGL(QWidget):
    """OpenGL 三维视图。"""

    MAX_TRAIL = 3000

    def __init__(self, travel: dict[str, float], backend=None) -> None:
        super().__init__()
        self._travel = travel
        self._backend = backend
        self._online = False
        self._trail: list[tuple[float, float, float]] = []
        zmax = travel["z"]

        self._gl = gl.GLViewWidget()
        self._gl.setBackgroundColor(QColor(16, 20, 28))
        self._gl.opts["distance"] = max(travel.values()) * 2.6
        self._gl.opts["elevation"] = 24
        self._gl.opts["azimuth"] = 40

        # 地板网格（XY 平面，z=0 为底部）
        g = gl.GLGridItem()
        g.setSize(x=travel["x"], y=travel["y"])
        g.setSpacing(x=travel["x"] / 10, y=travel["y"] / 10)
        g.setColor(QColor(70, 90, 120, 120))
        g.translate(travel["x"] / 2, travel["y"] / 2, 0)
        self._gl.addItem(g)

        # 行程线框
        edges = _box_edges(travel["x"], travel["y"], travel["z"])
        self._box = gl.GLLinePlotItem(pos=edges, color=QColor(90, 160, 255, 220), width=1.4, mode="lines", antialias=True)
        self._gl.addItem(self._box)

        # 坐标轴
        axis = gl.GLAxisItem()
        axis.setSize(x=travel["x"] * 0.18, y=travel["y"] * 0.18, z=travel["z"] * 0.18)
        self._gl.addItem(axis)

        # 轨迹（初始在顶部角点）
        self._trail_item = gl.GLLinePlotItem(
            pos=np.array([[0.0, 0.0, zmax]]), color=QColor(255, 200, 60, 200),
            width=2.0, mode="line_strip", antialias=True,
        )
        self._gl.addItem(self._trail_item)

        # 垂直吊线（末端点→XY 地板）
        self._drop = gl.GLLinePlotItem(
            pos=np.array([[0, 0, 0], [0, 0, zmax]]), color=QColor(255, 120, 60, 200),
            width=1.5, mode="lines", antialias=True,
        )
        self._gl.addItem(self._drop)

        # 地板投影点
        self._floor_pt = gl.GLScatterPlotItem(
            pos=np.array([[0.0, 0.0, 0.0]]),
            color=np.array([[0.6, 0.6, 0.6, 0.9]]), size=9.0, pxMode=True,
        )
        self._gl.addItem(self._floor_pt)

        # 末端点（大、亮；初始在上平面角点 (0,0,zmax)）
        self._point = gl.GLScatterPlotItem(
            pos=np.array([[0.0, 0.0, zmax]]),
            color=np.array([[1.0, 0.45, 0.2, 1.0]]), size=22.0, pxMode=True,
        )
        self._gl.addItem(self._point)

        # 顶部坐标读数
        self._overlay = _CoordOverlay(travel)

        # 四个按钮一行等宽对齐
        self.btn_clear = QPushButton("清除轨迹")
        self.btn_reset = QPushButton("复位视角")
        self.en_btn = QPushButton("使能全部")
        self.dis_btn = QPushButton("失能全部")
        self.btn_clear.clicked.connect(self.clear_trail)
        self.btn_reset.clicked.connect(self.reset_view)
        self.en_btn.setEnabled(False)
        self.en_btn.clicked.connect(lambda: backend.enable_all() if backend else None)
        self.dis_btn.setEnabled(False)
        self.dis_btn.setToolTip("失能后竖直 Z 轴可能因重力下落，慎用")
        self.dis_btn.clicked.connect(lambda: backend.disable_all() if backend else None)
        btn_row = QWidget()
        bh = QHBoxLayout(btn_row)
        bh.setContentsMargins(4, 0, 4, 2)
        for b in (self.btn_clear, self.btn_reset, self.en_btn, self.dis_btn):
            bh.addWidget(b, 1)  # 等宽对齐

        lay = QVBoxLayout(self)
        lay.setContentsMargins(2, 2, 2, 2)
        lay.addWidget(self._overlay)
        lay.addWidget(btn_row)
        lay.addWidget(self._gl, 1)

    def update_position(self, x: float, y: float, z: float) -> None:
        # Z 为竖直轴：0mm（原点）在上平面，正方向向下；
        # 故视图坐标 z_box = zmax - z_mm，使 0 显示在顶部。
        z_box = self._travel["z"] - z
        if not self._online:
            self._overlay._refresh(x, y, z)
            return
        pos = np.array([[x, y, z_box]], dtype=np.float64)
        self._point.setData(pos=pos)
        # 吊线：从末端点垂直到地板
        self._drop.setData(pos=np.array([[x, y, 0], [x, y, z_box]], dtype=np.float64))
        # 地板投影
        self._floor_pt.setData(pos=np.array([[x, y, 0]], dtype=np.float64))
        # 轨迹（去抖）
        thr = (self._travel["x"] * 0.002) ** 2
        if not self._trail or (x - self._trail[-1][0]) ** 2 + (y - self._trail[-1][1]) ** 2 + (z_box - self._trail[-1][2]) ** 2 > thr:
            self._trail.append((x, y, z_box))
            if len(self._trail) > self.MAX_TRAIL:
                self._trail = self._trail[-self.MAX_TRAIL:]
            self._trail_item.setData(pos=np.array(self._trail, dtype=np.float64))
        self._overlay._refresh(x, y, z)

    def clear_trail(self) -> None:
        self._trail.clear()
        self._trail_item.setData(pos=np.array([[0.0, 0.0, 0.0]]))

    def reset_view(self) -> None:
        self._gl.opts["elevation"] = 24
        self._gl.opts["azimuth"] = 40
        self._gl.opts["distance"] = max(self._travel.values()) * 2.6
        self._gl.update()

    def set_online(self, online: bool) -> None:
        self._online = online
        self._overlay.set_online(online)
        self.en_btn.setEnabled(online)
        self.dis_btn.setEnabled(online)


class View3DFallback(QWidget):
    """无 OpenGL 时的回退：三个 2D 投影视图 + 坐标读数。"""

    MAX_TRAIL = 3000

    def __init__(self, travel: dict[str, float], backend=None) -> None:
        super().__init__()
        self._travel = travel
        self._backend = backend
        self._online = False
        self._trails: dict[str, list] = {"xy": [], "xz": [], "yz": []}
        self._last = (0.0, 0.0, 0.0)

        pg.setConfigOption("background", "#10141c")
        pg.setConfigOption("foreground", "#d0d0d0")

        def mk(title: str, xl: str, yl: str, xmax: float, ymax: float, invert_y: bool = False) -> pg.PlotWidget:
            w = pg.PlotWidget(title=title)
            w.setLabel("bottom", xl, units="mm")
            w.setLabel("left", yl, units="mm")
            for x0, y0, x1, y1 in [
                (0, 0, xmax, 0), (xmax, 0, xmax, ymax), (xmax, ymax, 0, ymax), (0, ymax, 0, 0),
            ]:
                w.plot([x0, x1], [y0, y1], pen=pg.mkPen("#50a0ff", width=1))
            if invert_y:
                w.invertY(True)  # Z 轴：0 在顶部，向下增大
            w.setAspectLocked(True)
            return w

        self._xy = mk("俯视 XY", "X", "Y", travel["x"], travel["y"])
        self._xz = mk("正视 XZ", "X", "Z", travel["x"], travel["z"], invert_y=True)
        self._yz = mk("侧视 YZ", "Y", "Z", travel["y"], travel["z"], invert_y=True)

        self._cur = {
            "xy": self._xy.plot([], [], pen=None, symbol="o", symbolBrush=(255, 120, 60), symbolSize=14),
            "xz": self._xz.plot([], [], pen=None, symbol="o", symbolBrush=(255, 120, 60), symbolSize=14),
            "yz": self._yz.plot([], [], pen=None, symbol="o", symbolBrush=(255, 120, 60), symbolSize=14),
        }
        self._trail_plots = {
            "xy": self._xy.plot([], [], pen=pg.mkPen("#ffc800", width=1.4)),
            "xz": self._xz.plot([], [], pen=pg.mkPen("#ffc800", width=1.4)),
            "yz": self._yz.plot([], [], pen=pg.mkPen("#ffc800", width=1.4)),
        }

        self._overlay = _CoordOverlay(travel)

        # 四个按钮一行等宽对齐
        self.btn_clear = QPushButton("清除轨迹")
        self.btn_reset = QPushButton("复位视角")
        self.en_btn = QPushButton("使能全部")
        self.dis_btn = QPushButton("失能全部")
        self.btn_clear.clicked.connect(self.clear_trail)
        self.btn_reset.clicked.connect(self.reset_view)
        self.en_btn.setEnabled(False)
        self.en_btn.clicked.connect(lambda: backend.enable_all() if backend else None)
        self.dis_btn.setEnabled(False)
        self.dis_btn.setToolTip("失能后竖直 Z 轴可能因重力下落，慎用")
        self.dis_btn.clicked.connect(lambda: backend.disable_all() if backend else None)
        from PyQt5.QtWidgets import QHBoxLayout

        btn_row = QWidget()
        bh = QHBoxLayout(btn_row)
        bh.setContentsMargins(4, 0, 4, 2)
        for b in (self.btn_clear, self.btn_reset, self.en_btn, self.dis_btn):
            bh.addWidget(b, 1)  # 等宽对齐

        grid = QGridLayout()
        grid.addWidget(self._xy, 0, 0)
        grid.addWidget(self._xz, 0, 1)
        grid.addWidget(self._yz, 1, 0)
        place = QGroupBox("三视图")
        place.setLayout(grid)

        lay = QVBoxLayout(self)
        lay.setContentsMargins(2, 2, 2, 2)
        lay.addWidget(self._overlay)
        lay.addWidget(btn_row)
        lay.addWidget(place, 1)

    def update_position(self, x: float, y: float, z: float) -> None:
        self._last = (x, y, z)
        self._overlay._refresh(x, y, z)
        if not self._online:
            return
        self._cur["xy"].setData([x], [y])
        self._cur["xz"].setData([x], [z])
        self._cur["yz"].setData([y], [z])
        self._trails["xy"].append((x, y))
        self._trails["xz"].append((x, z))
        self._trails["yz"].append((y, z))
        for k in self._trails:
            if len(self._trails[k]) > self.MAX_TRAIL:
                self._trails[k] = self._trails[k][-self.MAX_TRAIL:]
            pts = self._trails[k]
            self._trail_plots[k].setData([p[0] for p in pts], [p[1] for p in pts])

    def clear_trail(self) -> None:
        for k in self._trails:
            self._trails[k].clear()
            self._trail_plots[k].setData([], [])

    def reset_view(self) -> None:
        for w in (self._xy, self._xz, self._yz):
            w.autoRange()

    def set_online(self, online: bool) -> None:
        self._online = online
        self._overlay.set_online(online)
        self.en_btn.setEnabled(online)
        self.dis_btn.setEnabled(online)


def make_3d_view(travel: dict[str, float], backend=None):
    if _HAS_OPENGL:
        try:
            return View3DOpenGL(travel, backend)
        except Exception:  # noqa: BLE001
            pass
    return View3DFallback(travel, backend)


def has_opengl() -> bool:
    return _HAS_OPENGL

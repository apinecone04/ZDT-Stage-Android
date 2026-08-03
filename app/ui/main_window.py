"""主窗口：装配 3D 视图与各功能面板，转发后台信号。"""

from __future__ import annotations

from PyQt5.QtCore import Qt
from PyQt5.QtGui import QFont, QKeySequence
from PyQt5.QtWidgets import (
    QFileDialog,
    QHBoxLayout,
    QMainWindow,
    QMessageBox,
    QSplitter,
    QStatusBar,
    QTabWidget,
    QVBoxLayout,
    QWidget,
)

from app.backend import Backend
from app.config import AXES, AppConfig
from app.ui.calibrate_panel import CalibratePanel
from app.ui.connection_widget import ConnectionWidget
from app.ui.goto_panel import GotoPanel
from app.ui.home_panel import HomePanel
from app.ui.jog_panel import JogPanel
from app.ui.status_panel import StatusPanel
from app.ui.teach_panel import TeachPanel
from app.ui.view3d import make_3d_view

STYLE = """
QMainWindow, QWidget { background: #1c2028; color: #e0e0e0; }
QGroupBox { border: 1px solid #3a4250; border-radius: 6px; margin-top: 10px;
            padding: 8px; font-weight: bold; }
QGroupBox::title { subcontrol-origin: margin; left: 10px; padding: 0 4px; }
QPushButton { background: #2c3340; border: 1px solid #445060; border-radius: 4px;
              padding: 6px 12px; }
QPushButton:hover { background: #354053; }
QPushButton:pressed { background: #1a1f28; }
QPushButton:disabled { color: #5a5a5a; background: #222831; }
QPushButton#estopBtn { background: #b02020; color: white; font-weight: bold; font-size: 14px; }
QPushButton#estopBtn:hover { background: #d02828; }
QPushButton#gotoBtn { background: #2f6d2a; }
QPushButton#gotoBtn:hover { background: #3a8233; }
QDoubleSpinBox, QSpinBox, QLineEdit, QComboBox { background: #262b34; color: #e0e0e0;
              border: 1px solid #445060; border-radius: 3px; padding: 3px; }
QTabWidget::pane { border: 1px solid #3a4250; }
QTabBar::tab { background: #262b34; padding: 6px 14px; margin-right: 2px; }
QTabBar::tab:selected { background: #3a4250; }
QTableWidget { background: #262b34; gridline-color: #3a4250; }
QHeaderView::section { background: #2c3340; }
QStatusBar { background: #11151c; }
QLabel { color: #e0e0e0; }
QTextEdit { background: #11151c; }
"""


class MainWindow(QMainWindow):
    def __init__(self, config: AppConfig) -> None:
        super().__init__()
        self.config = config
        self.setWindowTitle("ZDT 三维移动滑台 上位机")
        self.resize(1280, 800)

        self.backend = Backend(config)
        travel = {a: config.axes[a].travel_mm for a in AXES}
        self.view = make_3d_view(travel, self.backend)

        self.conn = ConnectionWidget(config, self.backend)
        self.jog = JogPanel(config, self.backend)
        self.goto = GotoPanel(config, self.backend)
        self.home = HomePanel(config, self.backend)
        self.teach = TeachPanel(config, self.backend)
        self.calib = CalibratePanel(config, self.backend)
        self.status = StatusPanel(config)

        self.tabs = QTabWidget()
        self.tabs.addTab(self.conn, "连接")
        self.tabs.addTab(self.jog, "点动")
        self.tabs.addTab(self.goto, "定位")
        self.tabs.addTab(self.home, "回零")
        self.tabs.addTab(self.teach, "示教")
        self.tabs.addTab(self.calib, "标定")
        self.tabs.addTab(self.status, "状态")

        # 右侧：连接面板置顶 + Tab
        right = QWidget()
        rl = QVBoxLayout(right)
        rl.setContentsMargins(0, 0, 0, 0)
        rl.addWidget(self.tabs, 1)

        splitter = QSplitter(Qt.Horizontal)
        splitter.addWidget(self.view if isinstance(self.view, QWidget) else QWidget())
        splitter.addWidget(right)
        splitter.setStretchFactor(0, 3)
        splitter.setStretchFactor(1, 2)
        splitter.setSizes([720, 520])

        self.setCentralWidget(splitter)
        self.setStatusBar(QStatusBar())
        self.setStyleSheet(STYLE)

        # 信号转发
        self.backend.status_updated.connect(self._on_status)
        self.backend.connection_changed.connect(self._on_connection)
        self.backend.error.connect(self._on_error)
        self.backend.command_result.connect(self._on_cmd)
        self.conn.connected.connect(self._on_connected)
        self.calib.calibration_applied.connect(self._on_calib_applied)

        self._build_menu()
        self._on_connection(False, {a: False for a in AXES})

    def _build_menu(self) -> None:
        mbar = self.menuBar()
        fm = mbar.addMenu("文件(&F)")
        act_import = fm.addAction("导入配置…")
        act_export = fm.addAction("导出配置…")
        fm.addSeparator()
        act_quit = fm.addAction("退出")
        act_quit.setShortcut(QKeySequence("Ctrl+Q"))
        act_import.triggered.connect(self._import_cfg)
        act_export.triggered.connect(self._export_cfg)
        act_quit.triggered.connect(self.close)

        vm = mbar.addMenu("视图(&V)")
        vm.addAction("清除轨迹", self._clear_trail).setShortcut(QKeySequence("Ctrl+L"))
        vm.addAction("复位视角", self._reset_view)

        hm = mbar.addMenu("急停(&E)")
        hm.addAction("急停全部", self.backend.estop).setShortcut(QKeySequence("Esc"))

        hlp = mbar.addMenu("帮助(&H)")
        hlp.addAction("关于", self._about)

    # ---- 信号处理 ----
    def _on_status(self, snaps: dict) -> None:
        # 分发给需要实时快照的面板
        for w in (self.goto, self.home, self.teach, self.calib, self.status):
            try:
                w.update_status(snaps)
            except AttributeError:
                pass
        # 3D 末端点
        self._update_3d(snaps)

    def _update_3d(self, snaps: dict) -> None:
        # 按可用轴独立更新；离线/未标定轴用 0（点停在对应坐标 0 处）
        coords = []
        for a in AXES:
            s = snaps.get(a)
            cal = self.config.axes[a].calibration
            if s and s.online and cal.valid and cal.angle_per_mm:
                coords.append(s.mm(cal))
            else:
                coords.append(0.0)
        self.view.update_position(coords[0], coords[1], coords[2])

    def _on_connection(self, connected: bool, online: dict) -> None:
        self.view.set_online(connected and any(online.values()))
        self.jog.set_online(connected, online)
        on = [a.upper() for a in AXES if online.get(a)]
        self.statusBar().showMessage(
            f"{'已连接' if connected else '未连接'} · 在线: {','.join(on) if on else '无'}", 0
        )

    def _on_connected(self, connected: bool) -> None:
        # 连接成功后切到点动页，方便操作
        if connected:
            self.tabs.setCurrentWidget(self.jog)

    def _on_error(self, msg: str) -> None:
        self.statusBar().showMessage(msg, 5000)

    def _on_cmd(self, axis: str, ok: bool, msg: str) -> None:
        self.statusBar().showMessage(f"{axis.upper()}: {msg} {'✓' if ok else '✗'}", 3000)

    def _on_calib_applied(self, axis: str) -> None:
        """某轴标定完成：刷新定位页标定标签。"""
        self.goto.refresh_calibration()

    # ---- 菜单动作 ----
    def _clear_trail(self) -> None:
        self.view.clear_trail()

    def _reset_view(self) -> None:
        self.view.reset_view()

    def _import_cfg(self) -> None:
        path, _ = QFileDialog.getOpenFileName(self, "导入配置", "", "JSON (*.json)")
        if not path:
            return
        try:
            import json
            from pathlib import Path

            with open(path, encoding="utf-8") as f:
                data = json.load(f)
            new_cfg = AppConfig.from_dict(data)
            # 替换引用（各面板持有旧 config 引用，逐字段拷贝）
            self._adopt_config(new_cfg)
            new_cfg.save()
            self.statusBar().showMessage("配置已导入", 3000)
        except Exception as exc:  # noqa: BLE001
            QMessageBox.critical(self, "导入失败", str(exc))

    def _export_cfg(self) -> None:
        path, _ = QFileDialog.getSaveFileName(self, "导出配置", "zdt_config.json", "JSON (*.json)")
        if not path:
            return
        try:
            self.config.save(__import__("pathlib").Path(path))
            self.statusBar().showMessage("配置已导出", 3000)
        except Exception as exc:  # noqa: BLE001
            QMessageBox.critical(self, "导出失败", str(exc))

    def _adopt_config(self, new_cfg: AppConfig) -> None:
        """把新配置的字段拷进当前 config（各面板共用同一引用）。"""
        c = self.config
        c.port = new_cfg.port
        c.baud = new_cfg.baud
        c.addresses = dict(new_cfg.addresses)
        c.poll_interval = new_cfg.poll_interval
        for a in AXES:
            c.axes[a] = new_cfg.axes[a]
        c.presets = list(new_cfg.presets)
        # 刷新 UI
        self.goto.refresh_calibration()
        self.teach._load_table()
        if hasattr(self.calib, "_refresh_state_label"):
            self.calib._refresh_state_label()

    def _about(self) -> None:
        QMessageBox.about(
            self,
            "关于",
            "<h3>ZDT 三维移动滑台上位机</h3>"
            "<p>PyQt5 + pyqtgraph，控制三轴 ZDT 闭环步进（RS485 同总线）。</p>"
            "<p>行程 X 60cm / Y 40cm / Z 30cm。需先标定后定位。</p>"
            "<p><small>基于 lyehe/ZDT_stepper 库</small></p>",
        )

    def closeEvent(self, event) -> None:  # noqa: D401, N802
        try:
            self.backend.shutdown()
        except Exception:  # noqa: BLE001
            pass
        self.config.save()
        super().closeEvent(event)

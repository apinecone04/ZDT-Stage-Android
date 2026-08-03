"""串口连接面板。"""

from __future__ import annotations

from typing import cast

from PyQt5.QtCore import Qt, pyqtSignal, pyqtSlot
from PyQt5.QtWidgets import (
    QComboBox,
    QFormLayout,
    QGroupBox,
    QHBoxLayout,
    QLabel,
    QPushButton,
    QSpinBox,
    QVBoxLayout,
    QWidget,
)

from app.backend import Backend, list_serial_ports
from app.config import AXES, AppConfig

BAUDS = [9600, 19200, 38400, 57600, 115200, 256000, 512000, 921600]


class ConnectionWidget(QWidget):
    """串口/波特率/三轴站号 + 连接/断开。"""

    connected = pyqtSignal(bool)  # 连接状态变化

    def __init__(self, config: AppConfig, backend: Backend) -> None:
        super().__init__()
        self.config = config
        self.backend = backend

        form = QFormLayout()
        self.port_box = QComboBox()
        self.port_box.setEditable(True)
        self.refresh_btn = QPushButton("刷新")
        self.refresh_btn.clicked.connect(self._refresh_ports)
        port_row = QWidget()
        ph = QHBoxLayout(port_row)
        ph.setContentsMargins(0, 0, 0, 0)
        ph.addWidget(self.port_box, 1)
        ph.addWidget(self.refresh_btn)

        self.baud_box = QComboBox()
        for b in BAUDS:
            self.baud_box.addItem(str(b), b)

        self.addr_boxes: dict[str, QSpinBox] = {}
        addr_row = QWidget()
        ah = QHBoxLayout(addr_row)
        ah.setContentsMargins(0, 0, 0, 0)
        for a in AXES:
            sb = QSpinBox()
            sb.setRange(1, 255)
            sb.setValue(config.addresses.get(a, AXES.index(a) + 1))
            self.addr_boxes[a] = sb
            ah.addWidget(QLabel(f"{a.upper()}:"))
            ah.addWidget(sb)

        form.addRow("串口", port_row)
        form.addRow("波特率", self.baud_box)
        form.addRow("站号 (1-255)", addr_row)

        self.connect_btn = QPushButton("连接")
        self.connect_btn.setObjectName("connectBtn")
        self.disconnect_btn = QPushButton("断开")
        self.disconnect_btn.setEnabled(False)
        self.connect_btn.clicked.connect(self._do_connect)
        self.disconnect_btn.clicked.connect(self._do_disconnect)

        bh = QHBoxLayout()
        bh.addWidget(self.connect_btn)
        bh.addWidget(self.disconnect_btn)

        self.status_lbl = QLabel("未连接")
        self.status_lbl.setAlignment(Qt.AlignCenter)

        lay = QVBoxLayout(self)
        box = QGroupBox("连接")
        bl = QVBoxLayout(box)
        bl.addLayout(form)
        bl.addLayout(bh)
        bl.addWidget(self.status_lbl)
        lay.addWidget(box)
        lay.addStretch(1)

        self._refresh_ports()
        self.baud_box.setCurrentText(str(config.baud))

        backend.connection_changed.connect(self._on_connection_changed)

    def _refresh_ports(self) -> None:
        cur = self.port_box.currentText()
        self.port_box.clear()
        ports = list_serial_ports()
        if not ports:
            ports = ["COM1", "COM2", "COM3"]  # 手动输入兜底
        self.port_box.addItems(ports)
        if cur and cur in ports:
            self.port_box.setCurrentText(cur)
        elif self.config.port and self.config.port in ports:
            self.port_box.setCurrentText(self.config.port)

    def _addr_map(self) -> dict[str, int]:
        return {a: int(sb.value()) for a, sb in self.addr_boxes.items()}

    def _do_connect(self) -> None:
        port = self.port_box.currentText().strip()
        if not port:
            self.status_lbl.setText("请选择串口")
            return
        baud = cast(int, self.baud_box.currentData())
        # 持久化
        self.config.port = port
        self.config.baud = baud
        self.config.addresses = self._addr_map()
        self.config.save()
        self.connect_btn.setEnabled(False)
        self.status_lbl.setText("连接中…")
        self.backend.connect(port, baud, self._addr_map())

    def _do_disconnect(self) -> None:
        self.disconnect_btn.setEnabled(False)
        self.backend.disconnect()

    @pyqtSlot(bool, object)
    def _on_connection_changed(self, connected: bool, online: dict) -> None:
        self.connect_btn.setEnabled(not connected)
        self.disconnect_btn.setEnabled(connected)
        if connected:
            on = [a.upper() for a in AXES if online.get(a)]
            self.status_lbl.setText(
                f"<font color='#3ad06c'>已连接 · 在线: {','.join(on) if on else '无'}</font>"
            )
        else:
            self.status_lbl.setText("<font color='#d06c3a'>未连接</font>")
        # 编辑可用性
        editable = not connected
        for w in (self.port_box, self.baud_box, *self.addr_boxes.values(), self.refresh_btn):
            w.setEnabled(editable)
        self.connected.emit(connected)

"""ZDT 三维移动滑台上位机 — 入口。

用法::

    python main.py

依赖：PyQt5、pyqtgraph、pyserial、PyYAML；可选 PyOpenGL（3D，否则回退三视图）。
控制库 ``ZDT_stepper-0.1.1`` 已在本仓内，由 ``app/__init__.py`` 自动加入 sys.path。
"""

from __future__ import annotations

import logging
import sys

# import app 触发库路径引导
import app  # noqa: F401
from app.config import AppConfig
from app.ui.main_window import MainWindow


def main() -> int:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    )
    from PyQt5.QtWidgets import QApplication

    # 高 DPI
    try:
        from PyQt5.QtCore import Qt

        QApplication.setAttribute(Qt.AA_EnableHighDpiScaling, True)  # type: ignore[attr-defined]
        QApplication.setAttribute(Qt.AA_UseHighDpiPixmaps, True)  # type: ignore[attr-defined]
    except Exception:  # noqa: BLE001
        pass

    app_qt = QApplication(sys.argv)
    app_qt.setApplicationName("ZDT 三维移动滑台上位机")

    config = AppConfig.load()
    win = MainWindow(config)
    win.show()
    return app_qt.exec_()


if __name__ == "__main__":
    raise SystemExit(main())

"""三维移动滑台 PyQt 上位机。

三轴 (X/Y/Z) ZDT 闭环步进电机，共用一根 RS485 总线，按站号寻址。
所有串口 I/O 在后台工作线程串行执行；GUI 通过 Qt 信号与之交互。
"""

from __future__ import annotations

import sys
from pathlib import Path

__version__ = "0.1.0"

# 引导同目录下的 ZDT 控制库（包名 stepper）到 import 路径，免 pip install。
_LIB_SRC = Path(__file__).resolve().parent.parent / "ZDT_stepper-0.1.1" / "src"
if _LIB_SRC.is_dir() and str(_LIB_SRC) not in sys.path:
    sys.path.insert(0, str(_LIB_SRC))

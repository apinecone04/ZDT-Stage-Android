"""轴状态快照与 mm↔counts 单位换算。"""

from __future__ import annotations

import time
from dataclasses import dataclass

from app.config import Calibration


@dataclass
class AxisSnapshot:
    """一次轮询采集的单轴状态快照（线程间传递的只读数据）。

    所有串口 I/O 在后台线程；快照经 Qt 信号投递到 GUI 线程。
    """

    axis: str
    online: bool
    # 原始编码器角度位置 (65536/转坐标系, 带符号)
    angle_position: int = 0
    # 目标位置 (同坐标系)
    target_position: int = 0
    # 实时速度 (RPM, 带符号)
    speed_rpm: float = 0.0
    # 母线电压 (V) 与相电流 (A)
    bus_voltage: float = 0.0
    phase_current: float = 0.0
    # 状态标志
    enabled: bool = False
    in_position: bool = False
    stalled: bool = False
    stall_protect: bool = False
    homing: bool = False
    homing_failed: bool = False
    timestamp: float = 0.0

    def __post_init__(self) -> None:
        if not self.timestamp:
            self.timestamp = time.monotonic()

    def mm(self, cal: Calibration) -> float:
        """由原始角度位置换算为相对原点的 mm。未标定返回 0。"""
        if not cal.valid or cal.angle_per_mm == 0:
            return 0.0
        raw = (self.angle_position - cal.origin_angle) / cal.angle_per_mm
        return -raw if cal.invert else raw

    def target_mm(self, cal: Calibration) -> float:
        if not cal.valid or cal.angle_per_mm == 0:
            return 0.0
        raw = (self.target_position - cal.origin_angle) / cal.angle_per_mm
        return -raw if cal.invert else raw


def delta_mm_to_pulses(delta_mm: float, cal: Calibration) -> int:
    """相对位移 mm -> move 指令的微步脉冲数 (带符号)。

    delta_mm 为显示坐标系的位移；invert 轴需先取反再换算。
    返回带符号整数：正=CW，负=CCW。未标定返回 0。
    """
    if not cal.valid or cal.pulses_per_mm == 0:
        return 0
    d = -delta_mm if cal.invert else delta_mm
    return int(round(d * cal.pulses_per_mm))

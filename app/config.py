"""配置数据模型与 JSON 持久化。

配置存于用户目录 ``~/.zdt_stage/config.json``，启动加载、变更即存。
"""

from __future__ import annotations

import json
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any

CONFIG_DIR = Path.home() / ".zdt_stage"
CONFIG_PATH = CONFIG_DIR / "config.json"

# 三轴默认行程 (mm)
DEFAULT_TRAVEL = {"x": 600.0, "y": 400.0, "z": 300.0}
AXES = ("x", "y", "z")


@dataclass
class Calibration:
    """单轴标定结果。

    ZDT 电机存在两套坐标系：``move`` 指令的 PulseCount 是微步脉冲，
    ``real_time_position`` 是 65536/转的编码器角度单位。因此标定分两套：
    ``pulses_per_mm`` 用于发 move，``angle_per_mm`` 用于由实时位置换算 mm。
    二者在同一次标定中测得，互不依赖。
    """

    pulses_per_mm: float = 0.0
    angle_per_mm: float = 0.0
    origin_angle: int = 0  # 设原点时记录的原始角度位置
    valid: bool = False

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)

    @classmethod
    def from_dict(cls, d: dict[str, Any]) -> "Calibration":
        return cls(
            pulses_per_mm=float(d.get("pulses_per_mm", 0.0)),
            angle_per_mm=float(d.get("angle_per_mm", 0.0)),
            origin_angle=int(d.get("origin_angle", 0)),
            valid=bool(d.get("valid", False)),
        )


@dataclass
class AxisConfig:
    """单轴配置。"""

    travel_mm: float = 0.0
    jog_speed: int = 60  # RPM
    jog_accel: int = 100  # 0..255
    move_speed: int = 120  # RPM
    move_accel: int = 150  # 0..255
    calibration: Calibration = field(default_factory=Calibration)

    def to_dict(self) -> dict[str, Any]:
        d = asdict(self)
        d["calibration"] = self.calibration.to_dict()
        return d

    @classmethod
    def from_dict(cls, d: dict[str, Any], axis: str) -> "AxisConfig":
        return cls(
            travel_mm=float(d.get("travel_mm", DEFAULT_TRAVEL[axis])),
            jog_speed=int(d.get("jog_speed", 60)),
            jog_accel=int(d.get("jog_accel", 100)),
            move_speed=int(d.get("move_speed", 120)),
            move_accel=int(d.get("move_accel", 150)),
            calibration=Calibration.from_dict(d.get("calibration", {})),
        )


@dataclass
class Preset:
    """预设点位 (示教)。"""

    name: str
    x: float = 0.0
    y: float = 0.0
    z: float = 0.0

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)

    @classmethod
    def from_dict(cls, d: dict[str, Any]) -> "Preset":
        return cls(
            name=str(d.get("name", "")),
            x=float(d.get("x", 0.0)),
            y=float(d.get("y", 0.0)),
            z=float(d.get("z", 0.0)),
        )


@dataclass
class AppConfig:
    """全局配置。"""

    port: str = ""
    baud: int = 115200
    addresses: dict[str, int] = field(default_factory=lambda: {"x": 1, "y": 2, "z": 3})
    poll_interval: float = 0.1
    axes: dict[str, AxisConfig] = field(default_factory=dict)
    presets: list[Preset] = field(default_factory=list)

    def __post_init__(self) -> None:
        if not self.axes:
            self.axes = {a: AxisConfig(travel_mm=DEFAULT_TRAVEL[a]) for a in AXES}

    def to_dict(self) -> dict[str, Any]:
        return {
            "port": self.port,
            "baud": self.baud,
            "addresses": dict(self.addresses),
            "poll_interval": self.poll_interval,
            "axes": {a: cfg.to_dict() for a, cfg in self.axes.items()},
            "presets": [p.to_dict() for p in self.presets],
        }

    @classmethod
    def from_dict(cls, d: dict[str, Any]) -> "AppConfig":
        cfg = cls()
        cfg.port = str(d.get("port", ""))
        cfg.baud = int(d.get("baud", 115200))
        cfg.addresses = {
            a: int(d.get("addresses", {}).get(a, i + 1))
            for i, a in enumerate(AXES)
        }
        cfg.poll_interval = float(d.get("poll_interval", 0.1))
        cfg.axes = {
            a: AxisConfig.from_dict(d.get("axes", {}).get(a, {}), a) for a in AXES
        }
        cfg.presets = [Preset.from_dict(p) for p in d.get("presets", [])]
        return cfg

    def save(self, path: Path = CONFIG_PATH) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            json.dump(self.to_dict(), f, ensure_ascii=False, indent=2)

    @classmethod
    def load(cls, path: Path = CONFIG_PATH) -> "AppConfig":
        if not path.exists():
            return cls()
        try:
            with open(path, encoding="utf-8") as f:
                return cls.from_dict(json.load(f))
        except (json.JSONDecodeError, OSError):
            return cls()

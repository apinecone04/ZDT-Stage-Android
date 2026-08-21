"""后台串口控制器（原始协议直发）。

ZDT_X42S 二代闭环步进的协议与开源库 v0.1.1 存在差异（get_version 帧长不同），
故此处不依赖库的 Device，而用 pyserial 直接收发原始帧。帧格式经真机验证。

所有串口 I/O 在后台 QThread 工作线程串行执行（共享总线、按站号寻址、无锁）。
"""

from __future__ import annotations

import logging
import queue
import time
from typing import Any, Callable

from PyQt5.QtCore import QObject, Qt, QThread, QTimer, pyqtSignal, pyqtSlot

from app.axis import AxisSnapshot, delta_mm_to_pulses
from app.config import AXES, AppConfig, Calibration

from serial import Serial
from serial.tools import list_ports

logger = logging.getLogger(__name__)

# ZDT 协议常量（FIXED 校验 = 0x6B，已真机验证）
CS = 0x6B
CODE = {
    "enable": 0xF3,
    "jog": 0xF6,
    "move": 0xFD,
    "estop": 0xFE,
    "set_home": 0x93,
    "home": 0x9A,
    "stop_home": 0x9C,
    "get_pos": 0x36,
    "get_target": 0x33,
    "get_sys_status": 0x43,
}
PROTO = {
    "enable": 0xAB,
    "estop": 0x98,
    "set_home": 0x88,
    "stop_home": 0x48,
    "get_sys_status": 0x7A,
}
STATUS_OK = 0x02
RESP_LEN = {
    "write": 4,   # addr + code + status + cs
    "get_pos": 8,
    "get_target": 8,
    "get_sys_status": 31,
}


def list_serial_ports() -> list[str]:
    return [p.device for p in list_ports.comports()]


def _to_signed(b: bytes) -> int:
    """符号 + 无符号整型：byte0 为符号(1=负)，其余大端。"""
    sign = -1 if (len(b) >= 1 and b[0] == 1) else 1
    return sign * int.from_bytes(b[1:], "big")


class ZDTController:
    """单轴原始协议控制器（操作共享 Serial，按地址寻址）。"""

    def __init__(self, serial: Serial, address: int) -> None:
        self.ser = serial
        self.addr = address

    def _txn(self, body: bytes, resp_len: int) -> bytes | None:
        """发一帧、读定长响应、校验地址与校验位；失败重试 3 次。"""
        frame = body + bytes([CS])
        for _ in range(3):
            try:
                self.ser.reset_input_buffer()
                self.ser.write(frame)
                resp = self.ser.read(resp_len)
                if len(resp) != resp_len:
                    continue
                if resp[0] != self.addr:
                    continue
                if resp[-1] != CS:
                    continue
                return resp
            except Exception:  # noqa: BLE001
                continue
        return None

    def _write(self, body: bytes) -> bool:
        resp = self._txn(body, RESP_LEN["write"])
        if resp is None:
            return False
        # code==0x00 表示错误帧（0xEE），否则 status==0x02 为成功
        return resp[1] != 0x00 and resp[2] == STATUS_OK

    # ---- 读 ----
    def get_sys_status(self) -> dict | None:
        resp = self._txn(bytes([self.addr, CODE["get_sys_status"], PROTO["get_sys_status"]]),
                         RESP_LEN["get_sys_status"])
        if resp is None or resp[1] != CODE["get_sys_status"]:
            return None
        d = resp[2:30]  # 28 字节数据，前 2 字节为 0x1F 0x09 协议头
        return {
            "bus_voltage_mv": int.from_bytes(d[2:4], "big"),
            "phase_current_ma": int.from_bytes(d[4:6], "big"),
            "encoder_value": int.from_bytes(d[6:8], "big"),
            "target_position": _to_signed(d[8:13]),
            "real_time_speed": _to_signed(d[13:16]),
            "real_time_position": _to_signed(d[16:21]),
            "position_error": _to_signed(d[21:26]),
            "homing_status": d[26],
            "motor_status": d[27],
        }

    def get_real_time_position(self) -> int | None:
        resp = self._txn(bytes([self.addr, CODE["get_pos"]]), RESP_LEN["get_pos"])
        if resp is None or resp[1] != CODE["get_pos"]:
            return None
        return _to_signed(resp[2:7])

    def get_target_position(self) -> int | None:
        resp = self._txn(bytes([self.addr, CODE["get_target"]]), RESP_LEN["get_target"])
        if resp is None or resp[1] != CODE["get_target"]:
            return None
        return _to_signed(resp[2:7])

    # ---- 写 ----
    def enable(self, sync: int = 0) -> bool:
        return self._write(bytes([self.addr, CODE["enable"], PROTO["enable"], 0x01, sync]))

    def disable(self, sync: int = 0) -> bool:
        return self._write(bytes([self.addr, CODE["enable"], PROTO["enable"], 0x00, sync]))

    def estop(self, sync: int = 0) -> bool:
        return self._write(bytes([self.addr, CODE["estop"], PROTO["estop"], sync]))

    def jog(self, cw: bool, speed: int, accel: int, sync: int = 0) -> bool:
        body = bytes([self.addr, CODE["jog"], 0x00 if cw else 0x01,
                      (speed >> 8) & 0xFF, speed & 0xFF, accel & 0xFF, sync])
        return self._write(body)

    def stop(self, sync: int = 0) -> bool:
        return self.jog(True, 0, 0, sync)

    def move_relative(self, cw: bool, pulses: int, speed: int, accel: int, sync: int = 0) -> bool:
        p = max(0, int(pulses)) & 0xFFFFFFFF
        body = bytes([self.addr, CODE["move"], 0x00 if cw else 0x01,
                      (speed >> 8) & 0xFF, speed & 0xFF, accel & 0xFF,
                      (p >> 24) & 0xFF, (p >> 16) & 0xFF, (p >> 8) & 0xFF, p & 0xFF,
                      0x00, sync])  # mode=0 相对
        return self._write(body)

    def move_absolute(self, target: int, speed: int, accel: int, sync: int = 0) -> bool:
        """绝对移动到目标位置（mode=1）。target 为电机内部位置坐标。"""
        p = int(target) & 0xFFFFFFFF
        body = bytes([self.addr, CODE["move"], 0x00,
                      (speed >> 8) & 0xFF, speed & 0xFF, accel & 0xFF,
                      (p >> 24) & 0xFF, (p >> 16) & 0xFF, (p >> 8) & 0xFF, p & 0xFF,
                      0x01, sync])  # mode=1 绝对
        return self._write(body)

    def home(self, homing_mode: int = 0, sync: int = 0) -> bool:
        return self._write(bytes([self.addr, CODE["home"], homing_mode & 0xFF, sync]))

    def set_home(self, store: int = 0) -> bool:
        return self._write(bytes([self.addr, CODE["set_home"], PROTO["set_home"], store]))

    def stop_home(self) -> bool:
        return self._write(bytes([self.addr, CODE["stop_home"], PROTO["stop_home"]]))


def _build_snapshot(axis: str, st: dict) -> AxisSnapshot:
    motor = st["motor_status"]
    homing = st["homing_status"]
    return AxisSnapshot(
        axis=axis,
        online=True,
        angle_position=st["real_time_position"],
        target_position=st["target_position"],
        speed_rpm=st["real_time_speed"],
        bus_voltage=st["bus_voltage_mv"] / 1000.0,
        phase_current=st["phase_current_ma"] / 1000.0,
        enabled=bool(motor & 0x01),
        in_position=bool(motor & 0x02),
        stalled=bool(motor & 0x04),
        stall_protect=bool(motor & 0x08),
        homing=bool(homing & 0x04),
        homing_failed=bool(homing & 0x08),
    )


class _Worker(QObject):
    status_updated = pyqtSignal(object)
    connection_changed = pyqtSignal(bool, object)
    command_result = pyqtSignal(str, bool, str)
    raw_read = pyqtSignal(str, int, int)
    error = pyqtSignal(str)
    _start_worker = pyqtSignal()

    def __init__(self, config: AppConfig) -> None:
        super().__init__()
        self.config = config
        self.serial: Serial | None = None
        self.controllers: dict[str, ZDTController] = {}
        self.online: dict[str, bool] = {a: False for a in AXES}
        self.cal: dict[str, Calibration] = {a: config.axes[a].calibration for a in AXES}
        self.motion: dict[str, dict[str, int]] = {
            a: {
                "jog_speed": config.axes[a].jog_speed,
                "jog_accel": config.axes[a].jog_accel,
                "move_speed": config.axes[a].move_speed,
                "move_accel": config.axes[a].move_accel,
            }
            for a in AXES
        }
        self.last_snap: dict[str, AxisSnapshot] = {
            a: AxisSnapshot(axis=a, online=False) for a in AXES
        }
        self.queue: queue.Queue[Callable[[], None]] = queue.Queue()
        self._poll_timer = QTimer(self)
        self._poll_timer.setTimerType(1)  # PreciseTimer
        self._poll_timer.timeout.connect(self._tick)
        self._last_poll = 0.0

    @pyqtSlot()
    def start(self) -> None:
        self._poll_timer.start(20)

    def submit(self, fn: Callable[[], None]) -> None:
        self.queue.put(fn)

    def _tick(self) -> None:
        drained = 0
        while drained < 20:
            try:
                fn = self.queue.get_nowait()
            except queue.Empty:
                break
            try:
                fn()
            except Exception as exc:  # noqa: BLE001
                logger.exception("command failed")
                self.error.emit(f"命令执行失败: {exc}")
            drained += 1
        now = time.monotonic()
        if self.serial and any(self.online.values()) and now - self._last_poll >= self.config.poll_interval:
            self._last_poll = now
            self._poll()

    def _poll(self) -> None:
        snaps: dict[str, AxisSnapshot] = {}
        for axis in AXES:
            c = self.controllers.get(axis)
            if not c or not self.online.get(axis):
                snaps[axis] = self.last_snap[axis]
                continue
            st = c.get_sys_status()
            if st is None:
                snap = self.last_snap[axis]
                snap.online = False
                snaps[axis] = snap
                continue
            snap = _build_snapshot(axis, st)
            snaps[axis] = snap
            self.last_snap[axis] = snap
        self.status_updated.emit(snaps)

    # ---- 连接 ----
    def _do_connect(self, port: str, baud: int, addr_map: dict[str, int]) -> None:
        self._do_disconnect()
        try:
            self.serial = Serial(port=port, baudrate=baud, timeout=0.15, write_timeout=0.15)
        except Exception as exc:  # noqa: BLE001
            self.error.emit(f"打开串口失败 {port}: {exc}")
            self.connection_changed.emit(False, {a: False for a in AXES})
            return
        time.sleep(0.1)
        for axis in AXES:
            addr = addr_map.get(axis, AXES.index(axis) + 1)
            c = ZDTController(self.serial, addr)
            st = c.get_sys_status()  # 探测在线
            if st is not None:
                self.controllers[axis] = c
                self.online[axis] = True
                self.last_snap[axis] = _build_snapshot(axis, st)
                try:
                    c.enable()
                except Exception:  # noqa: BLE001
                    logger.debug("enable %s failed", axis)
            else:
                self.online[axis] = False
                self.error.emit(f"{axis.upper()} (addr {addr}) 未应答，标离线")
        self.connection_changed.emit(True, dict(self.online))
        on = [a.upper() for a in AXES if self.online[a]]
        self.error.emit(f"已连接 {port}@{baud}，在线: {','.join(on) if on else '无'}")

    def _do_disconnect(self) -> None:
        # 注意：不主动 disable 电机——竖直 Z 轴去使能会因重力下落。
        # 电机使能状态由驱动板保持；用户可用“去使能”按钮手动断电。
        self.controllers.clear()
        self.online = {a: False for a in AXES}
        if self.serial is not None:
            try:
                self.serial.close()
            except Exception:  # noqa: BLE001
                pass
            self.serial = None
        self.connection_changed.emit(False, dict(self.online))

    # ---- 命令实现 ----
    def _ctl(self, axis: str) -> ZDTController | None:
        c = self.controllers.get(axis)
        if not c or not self.online.get(axis):
            self.command_result.emit(axis, False, "轴离线")
            return None
        return c

    def _jog(self, axis: str, speed: int, cw: bool) -> None:
        c = self._ctl(axis)
        if not c:
            return
        # 方向反转：invert 轴的 "+" 按键实际发 CCW，使“+”始终=+mm
        if self.cal[axis].invert:
            cw = not cw
        acc = self.motion[axis]["jog_accel"]
        ok = c.jog(cw, speed, acc)
        self.command_result.emit(axis, ok, f"jog {speed}rpm {'CW' if cw else 'CCW'}")

    def _stop_jog(self, axis: str) -> None:
        c = self._ctl(axis)
        if not c:
            return
        ok = c.stop()
        self.command_result.emit(axis, ok, "stop")

    def _goto_mm(self, axis: str, target_mm: float) -> None:
        c = self._ctl(axis)
        if not c:
            return
        cal = self.cal[axis]
        if not cal.valid or cal.pulses_per_mm == 0:
            self.command_result.emit(axis, False, "未标定，无法定位")
            return
        cur_mm = self.last_snap[axis].mm(cal)
        delta = target_mm - cur_mm
        pulses = delta_mm_to_pulses(delta, cal)
        if pulses == 0:
            self.command_result.emit(axis, True, "已在目标位置")
            return
        m = self.motion[axis]
        ok = c.move_relative(pulses > 0, abs(pulses), m["move_speed"], m["move_accel"])
        self.command_result.emit(axis, ok, f"goto {target_mm:.2f}mm (Δ{delta:.2f}mm/{pulses}p)")

    def _home(self, axis: str) -> None:
        c = self._ctl(axis)
        if not c:
            return
        ok = c.home()
        self.command_result.emit(axis, ok, "home")

    def _stop_home(self, axis: str) -> None:
        c = self._ctl(axis)
        if not c:
            return
        ok = c.stop_home()
        self.command_result.emit(axis, ok, "stop_home")

    def _set_home(self, axis: str) -> None:
        c = self._ctl(axis)
        if not c:
            return
        ok = c.set_home()
        self.cal[axis].origin_angle = 0
        self._persist_calibration(axis)
        self.command_result.emit(axis, ok, "set_home (原点置零)")

    def _set_origin_here(self, axis: str) -> None:
        cal = self.cal[axis]
        cal.origin_angle = self.last_snap[axis].angle_position
        self._persist_calibration(axis)
        self.command_result.emit(axis, True, "已设当前位置为原点")

    def _enable(self, axis: str) -> None:
        c = self._ctl(axis)
        if c:
            self.command_result.emit(axis, c.enable(), "enable")

    def _disable(self, axis: str) -> None:
        c = self._ctl(axis)
        if c:
            self.command_result.emit(axis, c.disable(), "disable")

    def _estop(self, axis: str | None) -> None:
        axes = AXES if axis is None else (axis,)
        for a in axes:
            c = self.controllers.get(a)
            if c and self.online.get(a):
                ok = c.estop()
                self.command_result.emit(a, ok, "estop")

    def _calibrate_move(self, axis: str, pulses: int) -> None:
        c = self._ctl(axis)
        if not c:
            return
        m = self.motion[axis]
        ok = c.move_relative(True, pulses, m["move_speed"], m["move_accel"])
        self.command_result.emit(axis, ok, f"calib move {pulses}p")

    def _read_raw(self, axis: str) -> None:
        c = self._ctl(axis)
        if not c:
            return
        pos = c.get_real_time_position()
        tgt = c.get_target_position()
        if pos is None or tgt is None:
            self.command_result.emit(axis, False, "read 失败")
            return
        self.raw_read.emit(axis, int(pos), int(tgt))

    def _apply_calibration(self, axis: str, cal: Calibration) -> None:
        self.cal[axis] = cal
        self._persist_calibration(axis)
        self.command_result.emit(axis, True, "标定已应用")

    def _set_invert(self, axis: str, invert: bool) -> None:
        self.cal[axis].invert = invert
        self.config.axes[axis].calibration.invert = invert
        self.config.save()
        self.command_result.emit(axis, True, f"方向反转={'开' if invert else '关'}")

    def _update_motion(self, axis: str, motion: dict[str, int]) -> None:
        self.motion[axis].update(motion)
        self.config.axes[axis].jog_speed = motion.get("jog_speed", self.motion[axis]["jog_speed"])
        self.config.axes[axis].jog_accel = motion.get("jog_accel", self.motion[axis]["jog_accel"])
        self.config.axes[axis].move_speed = motion.get("move_speed", self.motion[axis]["move_speed"])
        self.config.axes[axis].move_accel = motion.get("move_accel", self.motion[axis]["move_accel"])
        self.config.save()

    def _persist_calibration(self, axis: str) -> None:
        self.config.axes[axis].calibration = self.cal[axis]
        self.config.save()

    def stop(self) -> None:
        self._poll_timer.stop()
        self._do_disconnect()


class Backend(QObject):
    status_updated = pyqtSignal(object)
    connection_changed = pyqtSignal(bool, object)
    command_result = pyqtSignal(str, bool, str)
    raw_read = pyqtSignal(str, int, int)
    error = pyqtSignal(str)
    _start_worker = pyqtSignal()

    def __init__(self, config: AppConfig) -> None:
        super().__init__()
        self.config = config
        self._thread = QThread()
        self._worker = _Worker(config)
        self._worker.moveToThread(self._thread)
        self._thread.start()
        self._worker.status_updated.connect(self.status_updated)
        self._worker.connection_changed.connect(self.connection_changed)
        self._worker.command_result.connect(self.command_result)
        self._worker.raw_read.connect(self.raw_read)
        self._worker.error.connect(self.error)
        self._start_worker.connect(self._worker.start, Qt.QueuedConnection)
        self._start_worker.emit()

    def _q(self, fn: Callable[[], None]) -> None:
        self._worker.submit(fn)

    def connect(self, port: str, baud: int, addr_map: dict[str, int]) -> None:
        self._q(lambda: self._worker._do_connect(port, baud, addr_map))

    def disconnect(self) -> None:
        self._q(self._worker._do_disconnect)

    def jog(self, axis: str, speed: int, cw: bool) -> None:
        self._q(lambda: self._worker._jog(axis, speed, cw))

    def stop_jog(self, axis: str) -> None:
        self._q(lambda: self._worker._stop_jog(axis))

    def goto_mm(self, axis: str, target_mm: float) -> None:
        self._q(lambda: self._worker._goto_mm(axis, target_mm))

    def home(self, axis: str) -> None:
        self._q(lambda: self._worker._home(axis))

    def stop_home(self, axis: str) -> None:
        self._q(lambda: self._worker._stop_home(axis))

    def set_home(self, axis: str) -> None:
        self._q(lambda: self._worker._set_home(axis))

    def set_origin_here(self, axis: str) -> None:
        self._q(lambda: self._worker._set_origin_here(axis))

    def enable(self, axis: str) -> None:
        self._q(lambda: self._worker._enable(axis))

    def disable(self, axis: str) -> None:
        self._q(lambda: self._worker._disable(axis))

    def enable_all(self) -> None:
        self._q(lambda: [self._worker._enable(a) for a in AXES])

    def disable_all(self) -> None:
        self._q(lambda: [self._worker._disable(a) for a in AXES])

    def estop(self, axis: str | None = None) -> None:
        self._q(lambda: self._worker._estop(axis))

    def calibrate_move(self, axis: str, pulses: int) -> None:
        self._q(lambda: self._worker._calibrate_move(axis, pulses))

    def read_raw(self, axis: str) -> None:
        self._q(lambda: self._worker._read_raw(axis))

    def apply_calibration(self, axis: str, cal: Calibration) -> None:
        self._q(lambda: self._worker._apply_calibration(axis, cal))

    def set_invert(self, axis: str, invert: bool) -> None:
        self._q(lambda: self._worker._set_invert(axis, invert))

    def update_motion(self, axis: str, motion: dict[str, int]) -> None:
        self._q(lambda: self._worker._update_motion(axis, motion))

    def shutdown(self) -> None:
        self._q(self._worker.stop)
        self._thread.quit()
        self._thread.wait(2000)

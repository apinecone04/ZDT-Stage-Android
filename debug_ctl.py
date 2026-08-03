"""直接用 ZDTController 验证地址 3 能稳定获取状态 + 点动后位置变化。"""
import sys
import time

import app  # noqa: F401  路径引导（保留）
from app.backend import ZDTController
from serial import Serial

PORT = "COM10"
BAUD = 115200
ADDR = 3


def main():
    ser = Serial(PORT, BAUD, timeout=0.15, write_timeout=0.15)
    time.sleep(0.1)
    c = ZDTController(ser, ADDR)
    print("probe (get_sys_status):")
    st = c.get_sys_status()
    if st is None:
        print("  FAIL: no sys_status")
        ser.close()
        return 1
    print(f"  bus={st['bus_voltage_mv']}mV cur={st['phase_current_ma']}mA")
    print(f"  pos={st['real_time_position']} tgt={st['target_position']} spd={st['real_time_speed']}rpm")
    print(f"  motor=0x{st['motor_status']:02x} homing=0x{st['homing_status']:02x}")

    print("enable:", c.enable())
    time.sleep(0.2)
    print("jog CW 60rpm 0.6s:")
    print("  jog:", c.jog(True, 60, 200))
    time.sleep(0.6)
    c.stop()
    time.sleep(0.3)
    st = c.get_sys_status()
    print(f"  after jog: pos={st['real_time_position']} spd={st['real_time_speed']}rpm "
          f"motor=0x{st['motor_status']:02x}")
    c.disable()
    ser.close()
    print("OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())

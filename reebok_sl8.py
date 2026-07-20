#!/usr/bin/env python3
"""
Reebok SL8.0 BLE data reader.

Hardware:  Microchip BM70 BLE module (a.k.a. ISSC IS1678S).
Protocol:  F0-framed UART over ISSC Transparent UART GATT service.
OEM stack: Chang Yow — same as Domyos bikes in qdomyos-zwift.

Usage (from inside distrobox):
  DBUS_SYSTEM_BUS_ADDRESS=unix:path=/run/host/run/dbus/system_bus_socket \\
      python3 reebok_sl8.py

Or set the env var in your shell once:
  export DBUS_SYSTEM_BUS_ADDRESS=unix:path=/run/host/run/dbus/system_bus_socket
  python3 reebok_sl8.py
"""

import asyncio
import os
import struct
import sys

os.environ.setdefault(
    "DBUS_SYSTEM_BUS_ADDRESS",
    "unix:path=/run/host/run/dbus/system_bus_socket",
)

from bleak import BleakClient, BleakScanner
from bleak.backends.device import BLEDevice

# ── Device ────────────────────────────────────────────────────────────────────
DEVICE_ADDR = "E8:5D:86:BF:D4:C9"
DEVICE_PATH = "/org/bluez/hci0/dev_E8_5D_86_BF_D4_C9"

# ── ISSC Transparent UART service (49535343-fe7d-…) ───────────────────────────
# Confirmed from live GATT dump:
UART_SERVICE = "49535343-fe7d-4ae5-8fa9-9fafd205e455"
UART_TX      = "49535343-1e4d-4bd9-ba61-23c647249616"  # bike→us  (notify+write)
UART_RX      = "49535343-8841-43f4-a8d4-ecbe34729bb3"  # us→bike  (write only)

# ── Chang Yow handshake ───────────────────────────────────────────────────────
# Checksum rule: last byte = sum(all prior bytes) & 0xFF
INIT_SEQ = [
    bytes([0xf0, 0xa3, 0x93]),
    bytes([0xf0, 0xa4, 0x94]),
    bytes([0xf0, 0xa5, 0x95]),
    bytes([0xf0, 0xab, 0x9b]),
    bytes([0xf0, 0xc4, 0x03, 0xb7]),
]
CMD_NOOP = bytes([0xf0, 0xac, 0x9c])

CONNECT_RETRY_DELAY = 3.0   # seconds between connect retries
NOOP_INTERVAL       = 2.0   # seconds between keep-alive noops
ACK_TIMEOUT         = 1.5   # seconds to wait for init ack per step


def parse_data_packet(data: bytes) -> dict | None:
    """
    Parse 26-byte Chang Yow data notification.
    Layout confirmed from qdomyos-zwift domyosbike.cpp.
    """
    if len(data) < 26 or data[0] != 0xF0:
        return None
    return {
        "speed_kmh":   struct.unpack_from(">H", data, 6)[0]  / 10.0,
        "cadence_rpm": data[9],
        "calories":    struct.unpack_from(">H", data, 10)[0],
        "distance_km": struct.unpack_from(">H", data, 12)[0] / 10.0,
        "resistance":  data[14],
        "heart_rate":  data[18],
        "state":       "running" if data[22] == 0x06 else
                       ("stopped" if data[22] == 0x07 else f"0x{data[22]:02x}"),
    }


async def get_ble_device() -> BLEDevice:
    """
    Return a BLEDevice with the D-Bus path pre-populated so bleak skips
    the BLE scan (necessary when the device is already connected and not
    advertising).
    """
    # BLEDevice(address, name, details) — 'details' must have 'path' key
    # for the BlueZ backend to skip the scan in BleakClientBlueZDBus.connect.
    return BLEDevice(
        address=DEVICE_ADDR,
        name="REEBOK 0269",
        details={"path": DEVICE_PATH, "props": {}},
    )


async def run_session(client: BleakClient) -> None:
    """One connected session: handshake + live data loop."""
    fragment_buf: bytes = b""
    packet_count = 0
    init_ack = asyncio.Event()

    def on_notify(sender, raw: bytearray) -> None:
        nonlocal fragment_buf, packet_count
        data = bytes(raw)

        # Signal any waiting init step
        if data[0] == 0xF0:
            init_ack.set()

        # Reassemble fragmented packets (BLE MTU splits >20B payloads)
        if data[0] == 0xF0 and len(data) < 26:
            fragment_buf = data
            return
        if fragment_buf and data[0] != 0xF0:
            data = fragment_buf + data
            fragment_buf = b""

        packet_count += 1
        print(f"\n── #{packet_count} ({len(data)}B) {data.hex(' ')}")
        parsed = parse_data_packet(data)
        if parsed:
            print(
                f"   speed={parsed['speed_kmh']:.1f} km/h  "
                f"cadence={parsed['cadence_rpm']} rpm  "
                f"resistance={parsed['resistance']}  "
                f"cals={parsed['calories']}  "
                f"dist={parsed['distance_km']:.2f} km  "
                f"hr={parsed['heart_rate']} bpm  "
                f"state={parsed['state']}"
            )
        else:
            print(f"   (unparsed — header=0x{data[0]:02x} len={len(data)})")

    await client.start_notify(UART_TX, on_notify)
    print("  Notifications enabled on UART_TX.")

    # ── Chang Yow multi-step handshake ────────────────────────────────────────
    print("\nHandshake:")
    for i, cmd in enumerate(INIT_SEQ):
        init_ack.clear()
        await client.write_gatt_char(UART_RX, cmd, response=False)
        print(f"  [{i+1}/{len(INIT_SEQ)}] TX {cmd.hex()}  ", end="", flush=True)
        try:
            await asyncio.wait_for(init_ack.wait(), timeout=ACK_TIMEOUT)
            print("ack ✓")
        except asyncio.TimeoutError:
            print("(no ack)")

    print("\nHandshake done. Pedal the bike — Ctrl+C to stop.\n")

    while True:
        await asyncio.sleep(NOOP_INTERVAL)
        await client.write_gatt_char(UART_RX, CMD_NOOP, response=False)
        print("  [noop]", flush=True)


async def main() -> None:
    print(f"Targeting {DEVICE_ADDR}")
    dev = await get_ble_device()

    while True:
        try:
            print("Connecting …")
            async with BleakClient(dev, timeout=15.0) as client:
                print(f"Connected (services resolved).\n")
                await run_session(client)
        except KeyboardInterrupt:
            print("\nStopped by user.")
            sys.exit(0)
        except Exception as e:
            print(f"[{type(e).__name__}] {e}")
            print(f"Retrying in {CONNECT_RETRY_DELAY:.0f}s …")
            await asyncio.sleep(CONNECT_RETRY_DELAY)
            dev = await get_ble_device()  # refresh device object


if __name__ == "__main__":
    asyncio.run(main())

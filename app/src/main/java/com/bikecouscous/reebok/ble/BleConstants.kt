package com.bikecouscous.reebok.ble

import java.util.UUID

/**
 * Reebok SL8.0 BLE protocol constants.
 *
 * Hardware: Microchip BM70 BLE module (a.k.a. ISSC IS1678S).
 * Protocol: F0-framed UART over the ISSC Transparent UART GATT service.
 * OEM stack: Chang Yow -- same as Domyos bikes in qdomyos-zwift.
 */
object BleConstants {
    /** Default MAC address of the bike's BLE radio. Overridable in Settings. */
    const val DEFAULT_DEVICE_ADDRESS = "E8:5D:86:BF:D4:C9"

    val UART_SERVICE: UUID = UUID.fromString("49535343-fe7d-4ae5-8fa9-9fafd205e455")

    /** bike -> phone (notify) */
    val UART_TX: UUID = UUID.fromString("49535343-1e4d-4bd9-ba61-23c647249616")

    /** phone -> bike (write, no response) */
    val UART_RX: UUID = UUID.fromString("49535343-8841-43f4-a8d4-ecbe34729bb3")

    val CLIENT_CHARACTERISTIC_CONFIG: UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Checksum rule: last byte = sum(all prior bytes) & 0xFF. */
    val INIT_SEQ: List<ByteArray> = listOf(
        byteArrayOf(0xf0.toByte(), 0xa3.toByte(), 0x93.toByte()),
        byteArrayOf(0xf0.toByte(), 0xa4.toByte(), 0x94.toByte()),
        byteArrayOf(0xf0.toByte(), 0xa5.toByte(), 0x95.toByte()),
        byteArrayOf(0xf0.toByte(), 0xab.toByte(), 0x9b.toByte()),
        byteArrayOf(0xf0.toByte(), 0xc4.toByte(), 0x03, 0xb7.toByte()),
    )

    val CMD_NOOP: ByteArray = byteArrayOf(0xf0.toByte(), 0xac.toByte(), 0x9c.toByte())

    const val CONNECT_TIMEOUT_MS = 15_000L
    const val DISCOVER_SERVICES_TIMEOUT_MS = 10_000L
    const val WRITE_TIMEOUT_MS = 3_000L
    const val ACK_TIMEOUT_MS = 1_500L
    const val NOOP_INTERVAL_MS = 2_000L
    const val CONNECT_RETRY_DELAY_MS = 3_000L
    const val MAX_CONSECUTIVE_NOOP_FAILURES = 4
}

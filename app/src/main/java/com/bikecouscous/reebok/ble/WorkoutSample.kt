package com.bikecouscous.reebok.ble

import java.time.Instant

enum class BikeState { RUNNING, STOPPED, UNKNOWN }

/** One parsed 26-byte Chang Yow data notification. */
data class WorkoutSample(
    val timestamp: Instant,
    val speedKmh: Double,
    val cadenceRpm: Int,
    val calories: Int,
    val distanceKm: Double,
    val resistance: Int,
    val heartRateBpm: Int,
    val state: BikeState,
)

/**
 * Parse a 26-byte Chang Yow data notification.
 * Layout confirmed from qdomyos-zwift domyosbike.cpp.
 */
fun parseDataPacket(data: ByteArray, timestamp: Instant = Instant.now()): WorkoutSample? {
    if (data.size < 26 || data[0] != 0xF0.toByte()) return null

    fun u16(offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    val stateByte = data[22].toInt() and 0xFF
    return WorkoutSample(
        timestamp = timestamp,
        speedKmh = u16(6) / 10.0,
        cadenceRpm = data[9].toInt() and 0xFF,
        calories = u16(10),
        distanceKm = u16(12) / 10.0,
        resistance = data[14].toInt() and 0xFF,
        heartRateBpm = data[18].toInt() and 0xFF,
        state = when (stateByte) {
            0x06 -> BikeState.RUNNING
            0x07 -> BikeState.STOPPED
            else -> BikeState.UNKNOWN
        },
    )
}

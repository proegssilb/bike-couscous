package com.bikecouscous.reebok.recorder

import java.time.Instant

data class HeartRatePoint(val time: Instant, val bpm: Int)
data class SpeedPoint(val time: Instant, val kmh: Double)
data class CadencePoint(val time: Instant, val rpm: Int)

/** A finished, saveable workout: everything captured between start and stop. */
data class WorkoutSession(
    val startTime: Instant,
    val endTime: Instant,
    val heartRate: List<HeartRatePoint>,
    val speed: List<SpeedPoint>,
    val cadence: List<CadencePoint>,
    val distanceKm: Double,
    val calories: Int,
) {
    val isEmpty: Boolean get() = heartRate.isEmpty() && speed.isEmpty() && cadence.isEmpty() && distanceKm <= 0.0
}

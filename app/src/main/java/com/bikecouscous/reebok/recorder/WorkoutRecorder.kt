package com.bikecouscous.reebok.recorder

import com.bikecouscous.reebok.ble.WorkoutSample
import java.time.Instant

/**
 * Buffers live [WorkoutSample]s between a start() and stop() call into a
 * [WorkoutSession]. The bike's distance/calorie counters are cumulative
 * since the console was powered on (not reset per ride), so the session
 * totals are taken as the delta between the first and last sample seen
 * while recording.
 */
class WorkoutRecorder {
    private var startTime: Instant? = null
    private var startDistanceKm: Double? = null
    private var startCalories: Int? = null
    private var lastDistanceKm = 0.0
    private var lastCalories = 0

    private val heartRate = mutableListOf<HeartRatePoint>()
    private val speed = mutableListOf<SpeedPoint>()
    private val cadence = mutableListOf<CadencePoint>()

    val isRecording: Boolean get() = startTime != null

    fun start(at: Instant = Instant.now()) {
        startTime = at
        startDistanceKm = null
        startCalories = null
        lastDistanceKm = 0.0
        lastCalories = 0
        heartRate.clear()
        speed.clear()
        cadence.clear()
    }

    fun add(sample: WorkoutSample) {
        if (startTime == null) return
        if (startDistanceKm == null) startDistanceKm = sample.distanceKm
        if (startCalories == null) startCalories = sample.calories
        lastDistanceKm = sample.distanceKm
        lastCalories = sample.calories

        // Health Connect rejects heart rate samples outside 1..300 bpm; 0
        // means the strap isn't being read, so drop those points.
        if (sample.heartRateBpm in 1..300) {
            heartRate += HeartRatePoint(sample.timestamp, sample.heartRateBpm)
        }
        speed += SpeedPoint(sample.timestamp, sample.speedKmh)
        cadence += CadencePoint(sample.timestamp, sample.cadenceRpm)
    }

    /** Ends the session and returns it, or null if [start] was never called. */
    fun stop(at: Instant = Instant.now()): WorkoutSession? {
        val start = startTime ?: return null
        val end = if (at.isAfter(start)) at else start.plusMillis(1)
        val distanceDelta = (lastDistanceKm - (startDistanceKm ?: lastDistanceKm)).coerceAtLeast(0.0)
        val caloriesDelta = (lastCalories - (startCalories ?: lastCalories)).coerceAtLeast(0)

        val session = WorkoutSession(
            startTime = start,
            endTime = end,
            heartRate = heartRate.toList(),
            speed = speed.toList(),
            cadence = cadence.toList(),
            distanceKm = distanceDelta,
            calories = caloriesDelta,
        )
        startTime = null
        return session
    }

    fun discard() {
        startTime = null
    }
}

package com.bikecouscous.reebok.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Velocity
import com.bikecouscous.reebok.recorder.WorkoutSession
import java.time.ZoneId

enum class HealthConnectAvailability { AVAILABLE, NOT_INSTALLED, UNSUPPORTED }

/** Writes finished [WorkoutSession]s into Android Health Connect. */
class HealthConnectRepository(private val context: Context) {

    val permissions: Set<String> = setOf(
        HealthPermission.getWritePermission(ExerciseSessionRecord::class),
        HealthPermission.getWritePermission(HeartRateRecord::class),
        HealthPermission.getWritePermission(SpeedRecord::class),
        HealthPermission.getWritePermission(CyclingPedalingCadenceRecord::class),
        HealthPermission.getWritePermission(DistanceRecord::class),
        HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class),
    )

    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    fun availability(): HealthConnectAvailability = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> HealthConnectAvailability.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthConnectAvailability.NOT_INSTALLED
        else -> HealthConnectAvailability.UNSUPPORTED
    }

    suspend fun hasAllPermissions(): Boolean =
        client.permissionController.getGrantedPermissions().containsAll(permissions)

    suspend fun saveWorkout(session: WorkoutSession) {
        require(!session.isEmpty) { "Nothing was recorded" }

        val startZoneOffset = ZoneId.systemDefault().rules.getOffset(session.startTime)
        val endZoneOffset = ZoneId.systemDefault().rules.getOffset(session.endTime)
        // connect-client 1.1.0's Device.TYPE_* enum tops out at TYPE_SMART_DISPLAY --
        // no exercise-equipment type exists yet, so this is the honest choice.
        val device = Device(type = Device.TYPE_UNKNOWN, manufacturer = "Reebok", model = "SL8.0")
        val metadata = Metadata.activelyRecorded(device = device)

        val records = buildList {
            add(
                ExerciseSessionRecord(
                    startTime = session.startTime,
                    startZoneOffset = startZoneOffset,
                    endTime = session.endTime,
                    endZoneOffset = endZoneOffset,
                    metadata = metadata,
                    exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY,
                    title = "Reebok SL8.0 ride",
                ),
            )
            if (session.heartRate.isNotEmpty()) {
                add(
                    HeartRateRecord(
                        startTime = session.startTime,
                        startZoneOffset = startZoneOffset,
                        endTime = session.endTime,
                        endZoneOffset = endZoneOffset,
                        samples = session.heartRate.map { HeartRateRecord.Sample(it.time, it.bpm.toLong()) },
                        metadata = metadata,
                    ),
                )
            }
            if (session.speed.isNotEmpty()) {
                add(
                    SpeedRecord(
                        startTime = session.startTime,
                        startZoneOffset = startZoneOffset,
                        endTime = session.endTime,
                        endZoneOffset = endZoneOffset,
                        samples = session.speed.map {
                            SpeedRecord.Sample(it.time, Velocity.kilometersPerHour(it.kmh))
                        },
                        metadata = metadata,
                    ),
                )
            }
            if (session.cadence.isNotEmpty()) {
                add(
                    CyclingPedalingCadenceRecord(
                        startTime = session.startTime,
                        startZoneOffset = startZoneOffset,
                        endTime = session.endTime,
                        endZoneOffset = endZoneOffset,
                        samples = session.cadence.map {
                            CyclingPedalingCadenceRecord.Sample(it.time, it.rpm.toDouble())
                        },
                        metadata = metadata,
                    ),
                )
            }
            if (session.distanceKm > 0.0) {
                add(
                    DistanceRecord(
                        startTime = session.startTime,
                        startZoneOffset = startZoneOffset,
                        endTime = session.endTime,
                        endZoneOffset = endZoneOffset,
                        distance = Length.kilometers(session.distanceKm),
                        metadata = metadata,
                    ),
                )
            }
            if (session.calories > 0) {
                add(
                    TotalCaloriesBurnedRecord(
                        startTime = session.startTime,
                        startZoneOffset = startZoneOffset,
                        endTime = session.endTime,
                        endZoneOffset = endZoneOffset,
                        energy = Energy.kilocalories(session.calories.toDouble()),
                        metadata = metadata,
                    ),
                )
            }
        }

        client.insertRecords(records)
    }
}

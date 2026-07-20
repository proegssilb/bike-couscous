package com.bikecouscous.reebok.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.bikecouscous.reebok.MainActivity
import com.bikecouscous.reebok.R
import com.bikecouscous.reebok.ble.BikeState
import com.bikecouscous.reebok.ble.ReebokBleClient
import com.bikecouscous.reebok.ble.WorkoutSample
import com.bikecouscous.reebok.health.HealthConnectRepository
import com.bikecouscous.reebok.recorder.WorkoutRecorder
import com.bikecouscous.reebok.recorder.WorkoutSession
import com.bikecouscous.reebok.settings.DeviceAddressStore
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Owns the BLE connection and the in-progress workout for as long as a
 * recording session might be running, independent of whether the UI is in
 * the foreground. Bind to it for live state; it also runs as a foreground
 * service so Android doesn't tear down the BLE link while the screen is off.
 */
class RecordingService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var bleClient: ReebokBleClient
    private lateinit var healthConnectRepository: HealthConnectRepository
    private lateinit var deviceAddressStore: DeviceAddressStore
    private var bleStarted = false

    val recorder = WorkoutRecorder()

    private val _connectionState =
        MutableStateFlow<ReebokBleClient.ConnectionState>(ReebokBleClient.ConnectionState.Disconnected)
    val connectionState: StateFlow<ReebokBleClient.ConnectionState> = _connectionState.asStateFlow()

    private val _lastSample = MutableStateFlow<WorkoutSample?>(null)
    val lastSample: StateFlow<WorkoutSample?> = _lastSample.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _savedSessionEvents = MutableSharedFlow<Result<WorkoutSession>>(extraBufferCapacity = 1)
    val savedSessionEvents: SharedFlow<Result<WorkoutSession>> = _savedSessionEvents.asSharedFlow()

    inner class LocalBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    override fun onCreate() {
        super.onCreate()
        healthConnectRepository = HealthConnectRepository(applicationContext)
        deviceAddressStore = DeviceAddressStore(applicationContext)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Connecting to bike…"))
        if (!bleStarted) {
            bleStarted = true
            serviceScope.launch {
                val address = deviceAddressStore.deviceAddress.first()
                bleClient = ReebokBleClient(applicationContext, address)

                serviceScope.launch {
                    bleClient.connectionState.collect { state ->
                        _connectionState.value = state
                        updateNotification(state, _lastSample.value)
                    }
                }
                serviceScope.launch {
                    bleClient.samples.collect { sample ->
                        _lastSample.value = sample
                        if (recorder.isRecording) recorder.add(sample)
                        updateNotification(_connectionState.value, sample)
                    }
                }
                bleClient.start(serviceScope)
            }
        }
        return START_STICKY
    }

    fun startRecording() {
        recorder.start()
        _isRecording.value = true
    }

    fun stopRecordingAndSave() {
        val session = recorder.stop()
        _isRecording.value = false
        if (session == null || session.isEmpty) return
        serviceScope.launch {
            try {
                healthConnectRepository.saveWorkout(session)
                _savedSessionEvents.tryEmit(Result.success(session))
            } catch (e: Exception) {
                _savedSessionEvents.tryEmit(Result.failure(e))
            }
        }
    }

    fun discardRecording() {
        recorder.discard()
        _isRecording.value = false
    }

    override fun onDestroy() {
        if (::bleClient.isInitialized) bleClient.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Bike connection",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Shows the live connection to your exercise bike" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Bike Couscous")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(state: ReebokBleClient.ConnectionState, sample: WorkoutSample?) {
        val text = when (state) {
            is ReebokBleClient.ConnectionState.Disconnected -> "Disconnected"
            is ReebokBleClient.ConnectionState.Connecting -> "Connecting to bike…"
            is ReebokBleClient.ConnectionState.Handshaking -> "Waking up the bike…"
            is ReebokBleClient.ConnectionState.Retrying -> "Reconnecting…"
            is ReebokBleClient.ConnectionState.Streaming -> streamingText(sample)
        }
        val notification = buildNotification(text)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun streamingText(sample: WorkoutSample?): String {
        if (sample == null) return "Connected – waiting for data"
        val recording = if (_isRecording.value) "● REC  " else ""
        val riding = if (sample.state == BikeState.RUNNING) "riding" else "idle"
        return "$recording${sample.speedKmh.roundToInt()} km/h · ${sample.cadenceRpm} rpm · $riding"
    }

    companion object {
        private const val CHANNEL_ID = "bike_connection"
        private const val NOTIFICATION_ID = 1
    }
}

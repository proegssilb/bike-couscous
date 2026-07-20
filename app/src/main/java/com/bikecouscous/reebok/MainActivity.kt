package com.bikecouscous.reebok

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.bikecouscous.reebok.health.HealthConnectRepository
import com.bikecouscous.reebok.service.RecordingService
import com.bikecouscous.reebok.settings.DeviceAddressStore
import com.bikecouscous.reebok.ui.BikeScreen

class MainActivity : ComponentActivity() {

    private var service by mutableStateOf<RecordingService?>(null)
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as RecordingService.LocalBinder).getService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val healthConnectRepository = HealthConnectRepository(applicationContext)
        val deviceAddressStore = DeviceAddressStore(applicationContext)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BikeScreen(
                        service = service,
                        healthConnectRepository = healthConnectRepository,
                        deviceAddressStore = deviceAddressStore,
                        onConnect = ::startAndBindService,
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Reattach to RecordingService if it's already running -- e.g. the Activity was
        // recreated after being backgrounded with the screen off while a session was live.
        // Plain bindService (no BIND_AUTO_CREATE) only succeeds against an existing service,
        // so this never starts a new session just by opening the app. If it fails, the
        // service genuinely isn't running, so drop any stale reference from before.
        if (!isBound) {
            val intent = Intent(this, RecordingService::class.java)
            isBound = bindService(intent, connection, 0)
            if (!isBound) service = null
        }
    }

    override fun onStop() {
        // Unbind without clearing `service`: the object reference stays valid (the service
        // keeps running independently via startForegroundService) and this avoids a flash of
        // the disconnected screen on quick app-switches. onStart's rebind will confirm or
        // correct it.
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        super.onStop()
    }

    private fun startAndBindService() {
        val intent = Intent(this, RecordingService::class.java)
        ContextCompat.startForegroundService(this, intent)
        if (!isBound) {
            isBound = bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }
}

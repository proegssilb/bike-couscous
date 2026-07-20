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

    private fun startAndBindService() {
        val intent = Intent(this, RecordingService::class.java)
        ContextCompat.startForegroundService(this, intent)
        if (!isBound) {
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
            isBound = true
        }
    }

    override fun onDestroy() {
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        super.onDestroy()
    }
}

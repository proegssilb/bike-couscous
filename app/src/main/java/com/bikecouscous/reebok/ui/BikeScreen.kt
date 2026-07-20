package com.bikecouscous.reebok.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.health.connect.client.PermissionController
import com.bikecouscous.reebok.ble.ReebokBleClient
import com.bikecouscous.reebok.ble.WorkoutSample
import com.bikecouscous.reebok.health.HealthConnectAvailability
import com.bikecouscous.reebok.health.HealthConnectRepository
import com.bikecouscous.reebok.service.RecordingService
import com.bikecouscous.reebok.settings.DeviceAddressStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun BikeScreen(
    service: RecordingService?,
    healthConnectRepository: HealthConnectRepository,
    deviceAddressStore: DeviceAddressStore,
    onConnect: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var bluetoothGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
    }
    var notificationsGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
    }
    var healthConnectGranted by remember { mutableStateOf(false) }
    var deviceAddress by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        deviceAddress = deviceAddressStore.deviceAddress.first()
        healthConnectGranted = healthConnectRepository.hasAllPermissions()
    }

    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> bluetoothGranted = granted }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> notificationsGranted = granted }

    val healthPermissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) { granted -> healthConnectGranted = granted.containsAll(healthConnectRepository.permissions) }

    LaunchedEffect(service) {
        service?.savedSessionEvents?.collect { result ->
            result.fold(
                onSuccess = { session ->
                    val minutes = java.time.Duration.between(session.startTime, session.endTime).toMinutes()
                    snackbarHostState.showSnackbar("Saved ${minutes}min ride to Health Connect")
                },
                onFailure = { e -> snackbarHostState.showSnackbar("Couldn't save workout: ${e.message}") },
            )
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Bike Couscous", style = MaterialTheme.typography.headlineMedium)

            PermissionsCard(
                bluetoothGranted = bluetoothGranted,
                notificationsGranted = notificationsGranted,
                healthConnectAvailability = healthConnectRepository.availability(),
                healthConnectGranted = healthConnectGranted,
                onRequestBluetooth = { bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) },
                onRequestNotifications = {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
                onRequestHealthConnect = { healthPermissionLauncher.launch(healthConnectRepository.permissions) },
                onInstallHealthConnect = {
                    context.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("market://details?id=com.google.android.apps.healthdata"),
                        ),
                    )
                },
            )

            DeviceAddressCard(
                address = deviceAddress,
                enabled = service == null,
                onAddressChange = { deviceAddress = it },
                onSave = {
                    scope.launch {
                        deviceAddressStore.setDeviceAddress(deviceAddress)
                        Toast.makeText(context, "Saved. Reconnect to use it.", Toast.LENGTH_SHORT).show()
                    }
                },
            )

            if (service == null) {
                Button(
                    onClick = {
                        if (bluetoothGranted) onConnect() else bluetoothPermissionLauncher.launch(
                            Manifest.permission.BLUETOOTH_CONNECT,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Connect to bike") }
            } else {
                ConnectedPanel(service = service, healthConnectGranted = healthConnectGranted)
            }
        }
    }
}

@Composable
private fun PermissionsCard(
    bluetoothGranted: Boolean,
    notificationsGranted: Boolean,
    healthConnectAvailability: HealthConnectAvailability,
    healthConnectGranted: Boolean,
    onRequestBluetooth: () -> Unit,
    onRequestNotifications: () -> Unit,
    onRequestHealthConnect: () -> Unit,
    onInstallHealthConnect: () -> Unit,
) {
    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Setup", fontWeight = FontWeight.Bold)

            PermissionRow("Bluetooth", bluetoothGranted, onRequestBluetooth)
            PermissionRow("Notifications", notificationsGranted, onRequestNotifications)

            when (healthConnectAvailability) {
                HealthConnectAvailability.AVAILABLE ->
                    PermissionRow("Health Connect", healthConnectGranted, onRequestHealthConnect)
                HealthConnectAvailability.NOT_INSTALLED -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Health Connect app not installed")
                    OutlinedButton(onClick = onInstallHealthConnect) { Text("Install") }
                }
                HealthConnectAvailability.UNSUPPORTED -> Text("Health Connect isn't supported on this device")
            }
        }
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onRequest: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(if (granted) "$label ✓" else label)
        if (!granted) OutlinedButton(onClick = onRequest) { Text("Grant") }
    }
}

@Composable
private fun DeviceAddressCard(
    address: String,
    enabled: Boolean,
    onAddressChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Bike BLE address", fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = address,
                onValueChange = onAddressChange,
                enabled = enabled,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (enabled) {
                Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) { Text("Save") }
            } else {
                Text("Disconnect to change it", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ConnectedPanel(service: RecordingService, healthConnectGranted: Boolean) {
    val connectionState by service.connectionState.collectAsState()
    val sample by service.lastSample.collectAsState()
    val isRecording by service.isRecording.collectAsState()

    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Status: ${connectionState.describe()}", fontWeight = FontWeight.Bold)
            MetricsGrid(sample)
        }
    }

    val streaming = connectionState is ReebokBleClient.ConnectionState.Streaming
    if (!isRecording) {
        Button(
            onClick = { service.startRecording() },
            enabled = streaming && healthConnectGranted,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (healthConnectGranted) "Start workout" else "Grant Health Connect access first") }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { service.stopRecordingAndSave() }, modifier = Modifier.fillMaxWidth()) {
                Text("Stop & save")
            }
        }
    }
}

@Composable
private fun MetricsGrid(sample: WorkoutSample?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        MetricRow("Speed", sample?.let { "${it.speedKmh} km/h" } ?: "--")
        MetricRow("Cadence", sample?.let { "${it.cadenceRpm} rpm" } ?: "--")
        MetricRow("Resistance", sample?.let { "${it.resistance}" } ?: "--")
        MetricRow("Distance", sample?.let { "${it.distanceKm} km" } ?: "--")
        MetricRow("Calories", sample?.let { "${it.calories} kcal" } ?: "--")
        MetricRow("Heart rate", sample?.let { if (it.heartRateBpm > 0) "${it.heartRateBpm} bpm" else "no strap" } ?: "--")
        MetricRow("Bike state", sample?.state?.name ?: "--")
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

private fun ReebokBleClient.ConnectionState.describe(): String = when (this) {
    is ReebokBleClient.ConnectionState.Disconnected -> "Disconnected"
    is ReebokBleClient.ConnectionState.Connecting -> "Connecting…"
    is ReebokBleClient.ConnectionState.Handshaking -> "Waking up bike…"
    is ReebokBleClient.ConnectionState.Streaming -> "Connected"
    is ReebokBleClient.ConnectionState.Retrying -> "Reconnecting ($reason)…"
}

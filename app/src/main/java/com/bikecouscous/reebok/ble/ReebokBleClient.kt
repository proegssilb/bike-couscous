package com.bikecouscous.reebok.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "ReebokBleClient"

/**
 * Talks to a Reebok SL8.0 (Chang Yow / ISSC Transparent UART) exercise bike
 * over BLE and streams parsed [WorkoutSample]s. Connects directly by MAC
 * address (no scan, mirroring how the bike is normally already known/paired)
 * and keeps reconnecting for as long as [start] hasn't been [stop]ped.
 */
class ReebokBleClient(
    private val context: Context,
    private val deviceAddress: String,
) {

    sealed interface ConnectionState {
        data object Disconnected : ConnectionState
        data object Connecting : ConnectionState
        data object Handshaking : ConnectionState
        data object Streaming : ConnectionState
        data class Retrying(val reason: String) : ConnectionState
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _samples = MutableSharedFlow<WorkoutSample>(extraBufferCapacity = 64)
    val samples: SharedFlow<WorkoutSample> = _samples.asSharedFlow()

    private val ackSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val writeMutex = Mutex()

    private var gatt: BluetoothGatt? = null
    private var sessionJob: Job? = null

    private var connectResult: CompletableDeferred<Boolean>? = null
    private var servicesDiscovered: CompletableDeferred<Boolean>? = null
    private var pendingCharWrite: CompletableDeferred<Unit>? = null
    private var pendingDescriptorWrite: CompletableDeferred<Unit>? = null
    private var disconnectSignal: CompletableDeferred<Unit>? = null

    private var fragmentBuffer: ByteArray? = null

    fun start(scope: CoroutineScope) {
        if (sessionJob?.isActive == true) return
        sessionJob = scope.launch { runForever() }
    }

    fun stop() {
        sessionJob?.cancel()
        sessionJob = null
        closeGatt()
        _connectionState.value = ConnectionState.Disconnected
    }

    private suspend fun runForever() {
        while (currentCoroutineContext().isActive) {
            try {
                connectAndRunSession()
            } catch (e: Exception) {
                Log.w(TAG, "session ended: ${e.message}")
                _connectionState.value = ConnectionState.Retrying(e.message ?: "disconnected")
            }
            closeGatt()
            if (!currentCoroutineContext().isActive) break
            delay(BleConstants.CONNECT_RETRY_DELAY_MS)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectAndRunSession() {
        fragmentBuffer = null
        _connectionState.value = ConnectionState.Connecting

        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter
            ?: error("Bluetooth is not available on this device")
        check(adapter.isEnabled) { "Bluetooth is turned off" }

        val device: BluetoothDevice = adapter.getRemoteDevice(deviceAddress)

        connectResult = CompletableDeferred()
        servicesDiscovered = CompletableDeferred()
        disconnectSignal = CompletableDeferred()

        val g = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        gatt = g

        val connected = withTimeoutOrNull(BleConstants.CONNECT_TIMEOUT_MS) { connectResult!!.await() }
        check(connected == true) { "Connect timed out or failed" }

        val discovered = g.discoverServices() &&
            withTimeoutOrNull(BleConstants.DISCOVER_SERVICES_TIMEOUT_MS) { servicesDiscovered!!.await() } == true
        check(discovered) { "Service discovery failed" }

        val service = g.getService(BleConstants.UART_SERVICE) ?: error("UART service not found")
        val txChar = service.getCharacteristic(BleConstants.UART_TX) ?: error("TX characteristic not found")
        val rxChar = service.getCharacteristic(BleConstants.UART_RX) ?: error("RX characteristic not found")

        enableNotifications(g, txChar)

        _connectionState.value = ConnectionState.Handshaking
        for (cmd in BleConstants.INIT_SEQ) {
            if (disconnectSignal?.isCompleted == true) error("Disconnected during handshake")
            writeNoResponse(g, rxChar, cmd)
            withTimeoutOrNull(BleConstants.ACK_TIMEOUT_MS) { ackSignal.first() }
        }

        _connectionState.value = ConnectionState.Streaming
        var consecutiveFailures = 0
        while (currentCoroutineContext().isActive) {
            delay(BleConstants.NOOP_INTERVAL_MS)
            if (disconnectSignal?.isCompleted == true) error("Disconnected")
            val acked = writeNoResponse(g, rxChar, BleConstants.CMD_NOOP)
            consecutiveFailures = if (acked) 0 else consecutiveFailures + 1
            check(consecutiveFailures < BleConstants.MAX_CONSECUTIVE_NOOP_FAILURES) {
                "Lost connection (no ack for $consecutiveFailures keep-alives)"
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun enableNotifications(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        writeMutex.withLock {
            check(g.setCharacteristicNotification(characteristic, true)) {
                "Failed to enable local notifications"
            }
            val cccd = characteristic.getDescriptor(BleConstants.CLIENT_CHARACTERISTIC_CONFIG)
                ?: error("Notification descriptor missing")
            pendingDescriptorWrite = CompletableDeferred()
            val enqueued = g.writeDescriptorCompat(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            check(enqueued) { "Failed to write notification descriptor" }
            withTimeoutOrNull(BleConstants.WRITE_TIMEOUT_MS) { pendingDescriptorWrite!!.await() }
        }
    }

    /** Returns true if the write was acknowledged by the stack before [BleConstants.WRITE_TIMEOUT_MS]. */
    @SuppressLint("MissingPermission")
    private suspend fun writeNoResponse(
        g: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        payload: ByteArray,
    ): Boolean = writeMutex.withLock {
        pendingCharWrite = CompletableDeferred()
        val enqueued = g.writeCharacteristicCompat(
            characteristic,
            payload,
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
        )
        if (!enqueued) return@withLock false
        withTimeoutOrNull(BleConstants.WRITE_TIMEOUT_MS) { pendingCharWrite!!.await() } != null
    }

    private fun handleNotification(raw: ByteArray) {
        if (raw.isEmpty()) return

        if (raw[0] == 0xF0.toByte()) {
            ackSignal.tryEmit(Unit)
        }

        var data = raw
        if (data[0] == 0xF0.toByte() && data.size < 26) {
            fragmentBuffer = data
            return
        }
        val fragment = fragmentBuffer
        if (fragment != null && data[0] != 0xF0.toByte()) {
            data = fragment + data
            fragmentBuffer = null
        }

        parseDataPacket(data, Instant.now())?.let { _samples.tryEmit(it) }
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        gatt?.let {
            try {
                it.disconnect()
                it.close()
            } catch (e: Exception) {
                Log.w(TAG, "error closing gatt: ${e.message}")
            }
        }
        gatt = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> connectResult?.complete(true)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectResult?.complete(false)
                    disconnectSignal?.complete(Unit)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            servicesDiscovered?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            pendingDescriptorWrite?.complete(Unit)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            pendingCharWrite?.complete(Unit)
        }

        // Called on API < 33.
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleNotification(characteristic.value ?: return)
        }

        // Called on API >= 33 instead of the deprecated two-arg overload above.
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleNotification(value)
        }
    }
}

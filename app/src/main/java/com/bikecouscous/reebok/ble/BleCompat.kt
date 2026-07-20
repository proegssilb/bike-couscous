package com.bikecouscous.reebok.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothStatusCodes
import android.os.Build

/**
 * BluetoothGatt#writeCharacteristic(characteristic) and
 * BluetoothGattDescriptor#setValue() were deprecated in API 33 in favor of
 * variants that take the payload directly. Both call paths remain functional
 * on every supported API level; this just picks the non-deprecated one when
 * it's available so the app stays correct from API 26 through the latest.
 */
@SuppressLint("MissingPermission")
fun BluetoothGatt.writeCharacteristicCompat(
    characteristic: BluetoothGattCharacteristic,
    payload: ByteArray,
    writeType: Int,
): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        writeCharacteristic(characteristic, payload, writeType) == BluetoothStatusCodes.SUCCESS
    } else {
        @Suppress("DEPRECATION")
        characteristic.writeType = writeType
        @Suppress("DEPRECATION")
        characteristic.value = payload
        @Suppress("DEPRECATION")
        writeCharacteristic(characteristic)
    }
}

@SuppressLint("MissingPermission")
fun BluetoothGatt.writeDescriptorCompat(
    descriptor: BluetoothGattDescriptor,
    payload: ByteArray,
): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        writeDescriptor(descriptor, payload) == BluetoothStatusCodes.SUCCESS
    } else {
        @Suppress("DEPRECATION")
        descriptor.value = payload
        @Suppress("DEPRECATION")
        writeDescriptor(descriptor)
    }
}

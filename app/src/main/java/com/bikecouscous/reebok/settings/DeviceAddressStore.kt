package com.bikecouscous.reebok.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bikecouscous.reebok.ble.BleConstants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")
private val DEVICE_ADDRESS_KEY = stringPreferencesKey("device_address")

/** Persists the bike's BLE MAC address so it can be changed without a rebuild. */
class DeviceAddressStore(private val context: Context) {
    val deviceAddress: Flow<String> = context.dataStore.data.map {
        it[DEVICE_ADDRESS_KEY] ?: BleConstants.DEFAULT_DEVICE_ADDRESS
    }

    suspend fun setDeviceAddress(address: String) {
        context.dataStore.edit { it[DEVICE_ADDRESS_KEY] = address }
    }
}

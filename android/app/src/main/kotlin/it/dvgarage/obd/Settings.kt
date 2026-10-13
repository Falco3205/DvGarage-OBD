package it.dvgarage.obd

import android.content.Context

data class AdapterChoice(val name: String, val address: String, val ble: Boolean)

data class Account(
    val serverUrl: String,
    val token: String,
    val username: String,
    val displayName: String,
    val role: String,
) {
    val isAdmin: Boolean get() = role == "admin"
}

data class VehicleLink(val id: Long, val name: String, val key: String)

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("obd", Context.MODE_PRIVATE)

    fun serverUrl(): String = prefs.getString(SERVER_URL, "").orEmpty()

    fun account(): Account? {
        val token = prefs.getString(TOKEN, null) ?: return null
        return Account(
            serverUrl = serverUrl(),
            token = token,
            username = prefs.getString(USERNAME, "").orEmpty(),
            displayName = prefs.getString(DISPLAY_NAME, "").orEmpty(),
            role = prefs.getString(ROLE, "customer").orEmpty(),
        )
    }

    fun saveAccount(account: Account) {
        prefs.edit()
            .putString(SERVER_URL, account.serverUrl)
            .putString(TOKEN, account.token)
            .putString(USERNAME, account.username)
            .putString(DISPLAY_NAME, account.displayName)
            .putString(ROLE, account.role)
            .apply()
    }

    fun clearAccount() {
        prefs.edit()
            .remove(TOKEN).remove(USERNAME).remove(DISPLAY_NAME).remove(ROLE)
            .remove(VEHICLE_ID).remove(VEHICLE_NAME).remove(VEHICLE_KEY)
            .apply()
    }

    fun vehicle(): VehicleLink? {
        val key = prefs.getString(VEHICLE_KEY, null) ?: return null
        return VehicleLink(prefs.getLong(VEHICLE_ID, 0), prefs.getString(VEHICLE_NAME, "").orEmpty(), key)
    }

    fun saveVehicle(vehicle: VehicleLink?) {
        prefs.edit().apply {
            if (vehicle == null) {
                remove(VEHICLE_ID).remove(VEHICLE_NAME).remove(VEHICLE_KEY)
            } else {
                putLong(VEHICLE_ID, vehicle.id).putString(VEHICLE_NAME, vehicle.name).putString(VEHICLE_KEY, vehicle.key)
            }
        }.apply()
    }

    fun adapter(): AdapterChoice? {
        val address = prefs.getString(ADAPTER_ADDRESS, null) ?: return null
        return AdapterChoice(
            prefs.getString(ADAPTER_NAME, address).orEmpty(),
            address,
            prefs.getBoolean(ADAPTER_BLE, false),
        )
    }

    fun saveAdapter(adapter: AdapterChoice) {
        prefs.edit()
            .putString(ADAPTER_ADDRESS, adapter.address)
            .putString(ADAPTER_NAME, adapter.name)
            .putBoolean(ADAPTER_BLE, adapter.ble)
            .apply()
    }

    private companion object {
        const val SERVER_URL = "server_url"
        const val TOKEN = "token"
        const val USERNAME = "username"
        const val DISPLAY_NAME = "display_name"
        const val ROLE = "role"
        const val VEHICLE_ID = "vehicle_id"
        const val VEHICLE_NAME = "vehicle_name"
        const val VEHICLE_KEY = "vehicle_key"
        const val ADAPTER_ADDRESS = "adapter_address"
        const val ADAPTER_NAME = "adapter_name"
        const val ADAPTER_BLE = "adapter_ble"
    }
}

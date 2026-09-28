package it.dvgarage.obd

import android.content.Context

data class AdapterChoice(val name: String, val address: String, val ble: Boolean)

data class Config(
    val serverUrl: String,
    val deviceKey: String,
    val adapter: AdapterChoice?,
) {
    val isComplete: Boolean
        get() = serverUrl.isNotBlank() && deviceKey.isNotBlank() && adapter != null
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("obd", Context.MODE_PRIVATE)

    fun load(): Config {
        val address = prefs.getString(ADAPTER_ADDRESS, null)
        return Config(
            serverUrl = prefs.getString(SERVER_URL, "").orEmpty(),
            deviceKey = prefs.getString(DEVICE_KEY, "").orEmpty(),
            adapter = address?.let {
                AdapterChoice(prefs.getString(ADAPTER_NAME, it).orEmpty(), it, prefs.getBoolean(ADAPTER_BLE, false))
            },
        )
    }

    fun save(config: Config) {
        prefs.edit()
            .putString(SERVER_URL, config.serverUrl.trim())
            .putString(DEVICE_KEY, config.deviceKey.trim())
            .putString(ADAPTER_ADDRESS, config.adapter?.address)
            .putString(ADAPTER_NAME, config.adapter?.name)
            .putBoolean(ADAPTER_BLE, config.adapter?.ble ?: false)
            .apply()
    }

    private companion object {
        const val SERVER_URL = "server_url"
        const val DEVICE_KEY = "device_key"
        const val ADAPTER_ADDRESS = "adapter_address"
        const val ADAPTER_NAME = "adapter_name"
        const val ADAPTER_BLE = "adapter_ble"
    }
}

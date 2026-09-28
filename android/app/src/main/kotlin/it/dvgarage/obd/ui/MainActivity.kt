package it.dvgarage.obd.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.dvgarage.obd.AdapterChoice
import it.dvgarage.obd.Config
import it.dvgarage.obd.Settings
import it.dvgarage.obd.bluetooth.Adapters
import it.dvgarage.obd.service.AppState
import it.dvgarage.obd.service.ObdService
import it.dvgarage.obd.service.Phase

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Root() }
    }

    @Composable
    private fun Root() {
        val settings = remember { Settings(this) }
        val adapters = remember { Adapters(this) }
        val saved = remember { settings.load() }
        var serverUrl by remember { mutableStateOf(saved.serverUrl) }
        var deviceKey by remember { mutableStateOf(saved.deviceKey) }
        var selected by remember { mutableStateOf(saved.adapter) }
        var scanning by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf<String?>(null) }
        val choices = remember { mutableStateListOf<AdapterChoice>() }
        val state by AppState.state.collectAsStateWithLifecycle()

        fun refreshPaired() {
            choices.removeAll { !it.ble }
            choices.addAll(0, adapters.paired())
        }

        fun scan() {
            scanning = true
            adapters.scanBle(
                SCAN_DURATION_MS,
                onFound = { found -> if (choices.none { it.address == found.address }) choices += found },
                onFinished = { scanning = false },
            )
        }

        val permissions = remember { requiredPermissions() }
        var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) {
                pendingAction?.invoke()
            } else {
                message = "Bluetooth permissions are required"
            }
            pendingAction = null
        }

        fun withPermissions(action: () -> Unit) {
            pendingAction = action
            launcher.launch(permissions)
        }

        fun start() {
            val config = Config(serverUrl, deviceKey, selected)
            if (!config.isComplete) {
                message = "Fill in server, device key and adapter"
                return
            }
            if (!adapters.isEnabled) {
                message = "Turn Bluetooth on first"
                return
            }
            message = null
            settings.save(config)
            ObdService.start(this)
        }

        MainScreen(
            state = state,
            serverUrl = serverUrl,
            deviceKey = deviceKey,
            selected = selected,
            choices = choices,
            scanning = scanning,
            message = message,
            onServerUrl = { serverUrl = it },
            onDeviceKey = { deviceKey = it },
            onSelect = { selected = it },
            onRefreshPaired = { withPermissions(::refreshPaired) },
            onScan = { withPermissions(::scan) },
            onStart = { withPermissions(::start) },
            onStop = { ObdService.stop(this) },
            running = state.phase != Phase.STOPPED,
        )
    }

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            buildList {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            }.toTypedArray()
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private companion object {
        const val SCAN_DURATION_MS = 8_000L
    }
}

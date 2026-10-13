package it.dvgarage.obd.ui

import android.Manifest
import android.content.Intent
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
import it.dvgarage.obd.Account
import it.dvgarage.obd.AdapterChoice
import it.dvgarage.obd.Settings
import it.dvgarage.obd.VehicleLink
import it.dvgarage.obd.bluetooth.Adapters
import it.dvgarage.obd.server.ApiException
import it.dvgarage.obd.server.CustomerInfo
import it.dvgarage.obd.server.UserApi
import it.dvgarage.obd.server.VehicleInfo
import it.dvgarage.obd.service.AppState
import it.dvgarage.obd.service.ObdService
import it.dvgarage.obd.service.Phase
import it.dvgarage.obd.service.TraceLog
import it.dvgarage.obd.service.WorkshopRunner
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Root() }
    }

    private fun <T> background(work: () -> T, done: (Result<T>) -> Unit) {
        thread(name = "obd-ui-task") {
            val result = runCatching(work)
            runOnUiThread { done(result) }
        }
    }

    private fun describe(error: Throwable): String = when (error) {
        is ApiException -> error.message.orEmpty()
        else -> error.message ?: "Server non raggiungibile"
    }

    @Composable
    private fun Root() {
        val settings = remember { Settings(this) }
        val adapters = remember { Adapters(this) }
        val workshop = remember { WorkshopRunner.get(this) }

        var account by remember { mutableStateOf(settings.account()) }
        var loginBusy by remember { mutableStateOf(false) }
        var loginError by remember { mutableStateOf<String?>(null) }
        var tab by remember { mutableStateOf(HomeTab.VEHICLE) }

        var adapter by remember { mutableStateOf(settings.adapter()) }
        var scanning by remember { mutableStateOf(false) }
        val choices = remember { mutableStateListOf<AdapterChoice>() }

        var linked by remember { mutableStateOf(settings.vehicle()) }
        var vehicles by remember { mutableStateOf<List<VehicleInfo>>(emptyList()) }
        var newVehicleName by remember { mutableStateOf("") }
        var vehicleBusy by remember { mutableStateOf(false) }
        var vehicleMessage by remember { mutableStateOf<String?>(null) }

        var customers by remember { mutableStateOf<List<CustomerInfo>>(emptyList()) }
        var customerId by remember { mutableStateOf<Long?>(null) }
        var reportLabel by remember { mutableStateOf("Lettura officina") }
        var saving by remember { mutableStateOf(false) }
        var saveMessage by remember { mutableStateOf<String?>(null) }

        val monitor by AppState.state.collectAsStateWithLifecycle()
        val workshopState by workshop.state.collectAsStateWithLifecycle()
        val traceLines by TraceLog.lines.collectAsStateWithLifecycle()
        val monitorRunning = monitor.phase != Phase.STOPPED

        val permissions = remember { requiredPermissions() }
        var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) pendingAction?.invoke() else vehicleMessage = "Servono i permessi Bluetooth"
            pendingAction = null
        }

        fun withPermissions(action: () -> Unit) {
            pendingAction = action
            launcher.launch(permissions)
        }

        fun signOutLocally(message: String? = null) {
            ObdService.stop(this)
            settings.clearAccount()
            account = null
            linked = null
            vehicles = emptyList()
            customers = emptyList()
            workshop.reset()
            tab = HomeTab.VEHICLE
            loginError = message
        }

        fun onApiFailure(error: Throwable): String {
            if (error is ApiException && error.status == 401) {
                signOutLocally("Sessione scaduta, accedi di nuovo")
                return ""
            }
            return describe(error)
        }

        fun loadVehicles(current: Account) {
            vehicleBusy = true
            background({ UserApi(current.serverUrl, current.token).vehicles() }) { result ->
                vehicleBusy = false
                result.onSuccess { vehicles = it }.onFailure { vehicleMessage = onApiFailure(it) }
            }
        }

        fun loadCustomers(current: Account) {
            if (!current.isAdmin) return
            background({ UserApi(current.serverUrl, current.token).customers() }) { result ->
                result.onSuccess { customers = it }.onFailure { saveMessage = onApiFailure(it) }
            }
        }

        fun refreshPaired() {
            choices.removeAll { !it.ble }
            choices.addAll(0, adapters.paired())
        }

        fun scanBle() {
            scanning = true
            adapters.scanBle(
                8_000L,
                onFound = { found -> if (choices.none { it.address == found.address }) choices += found },
                onFinished = { scanning = false },
            )
        }

        fun selectAdapter(choice: AdapterChoice) {
            adapter = choice
            settings.saveAdapter(choice)
        }

        fun startMonitor() {
            if (linked == null) {
                vehicleMessage = "Scegli o crea un veicolo"
                return
            }
            if (adapter == null) {
                vehicleMessage = "Scegli l'adattatore OBD"
                return
            }
            if (!adapters.isEnabled) {
                vehicleMessage = "Attiva il Bluetooth"
                return
            }
            vehicleMessage = null
            ObdService.start(this)
        }

        fun readWorkshop(clear: Boolean) {
            val chosen = adapter
            if (chosen == null) {
                saveMessage = "Scegli l'adattatore OBD"
                return
            }
            if (!adapters.isEnabled) {
                saveMessage = "Attiva il Bluetooth"
                return
            }
            saveMessage = null
            if (clear) workshop.clear(chosen) else workshop.read(chosen)
        }

        fun saveReport(current: Account) {
            val snapshot = workshopState.snapshot ?: return
            saving = true
            val cleared = workshopState.cleared
            val owner = customerId
            val label = reportLabel.trim()
            background({ UserApi(current.serverUrl, current.token).saveWorkshopRead(label, owner, snapshot, cleared) }) { result ->
                saving = false
                saveMessage = result.fold({ "Lettura salvata" }, { onApiFailure(it) })
            }
        }

        fun login(serverUrl: String, username: String, password: String) {
            loginBusy = true
            loginError = null
            val url = serverUrl.trim().trimEnd('/')
            background({ UserApi.login(url, username.trim(), password) }) { result ->
                loginBusy = false
                result.onSuccess { session ->
                    val signedIn = Account(url, session.token, session.username, session.displayName, session.role)
                    settings.saveAccount(signedIn)
                    account = signedIn
                    loadVehicles(signedIn)
                    loadCustomers(signedIn)
                }.onFailure { loginError = describe(it) }
            }
        }

        fun logout(current: Account) {
            background({ runCatching { UserApi(current.serverUrl, current.token).logout() } }) { }
            signOutLocally()
        }

        val current = account
        if (current == null) {
            LoginScreen(settings.serverUrl(), loginBusy, loginError, ::login)
            return
        }

        androidx.compose.runtime.LaunchedEffect(current.token) {
            loadVehicles(current)
            loadCustomers(current)
        }

        val picker: @Composable () -> Unit = {
            AdapterPicker(
                choices = choices,
                selected = adapter,
                scanning = scanning,
                enabled = !monitorRunning && !workshopState.busy,
                onSelect = ::selectAdapter,
                onPaired = { withPermissions(::refreshPaired) },
                onScan = { withPermissions(::scanBle) },
            )
        }

        HomeScreen(current, tab, { tab = it }, { logout(current) }) {
            when (tab) {
                HomeTab.VEHICLE -> VehicleTab(
                    state = monitor,
                    linked = linked,
                    vehicles = vehicles,
                    newVehicleName = newVehicleName,
                    message = vehicleMessage,
                    busy = vehicleBusy || workshopState.busy,
                    running = monitorRunning,
                    adapterPicker = picker,
                    onNewVehicleName = { newVehicleName = it },
                    onCreateVehicle = {
                        vehicleBusy = true
                        vehicleMessage = null
                        val name = newVehicleName.trim()
                        background({ UserApi(current.serverUrl, current.token).createVehicle(name) }) { result ->
                            vehicleBusy = false
                            result.onSuccess { vehicle: VehicleLink ->
                                settings.saveVehicle(vehicle)
                                linked = vehicle
                                newVehicleName = ""
                                loadVehicles(current)
                            }.onFailure { vehicleMessage = onApiFailure(it) }
                        }
                    },
                    onLinkVehicle = { vehicle ->
                        vehicleBusy = true
                        vehicleMessage = null
                        background({ UserApi(current.serverUrl, current.token).linkVehicle(vehicle.id) }) { result ->
                            vehicleBusy = false
                            result.onSuccess { link ->
                                settings.saveVehicle(link)
                                linked = link
                            }.onFailure { vehicleMessage = onApiFailure(it) }
                        }
                    },
                    onReloadVehicles = { loadVehicles(current) },
                    onStart = { withPermissions(::startMonitor) },
                    onStop = { ObdService.stop(this@MainActivity) },
                )

                HomeTab.WORKSHOP -> WorkshopTab(
                    state = workshopState,
                    customers = customers,
                    customerId = customerId,
                    label = reportLabel,
                    monitorRunning = monitorRunning,
                    saveMessage = saveMessage,
                    savingReport = saving,
                    adapterPicker = picker,
                    onCustomer = { customerId = it },
                    onLabel = { reportLabel = it },
                    onRead = { withPermissions { readWorkshop(clear = false) } },
                    onClear = { withPermissions { readWorkshop(clear = true) } },
                    onSave = { saveReport(current) },
                    onReset = {
                        workshop.reset()
                        saveMessage = null
                    },
                )

                HomeTab.LOG -> LogTab(
                    lines = traceLines,
                    onShare = {
                        val send = Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, TraceLog.text())
                        startActivity(Intent.createChooser(send, "Condividi log"))
                    },
                    onClear = TraceLog::clear,
                )
            }
        }
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
}

package it.dvgarage.obd.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import it.dvgarage.obd.AdapterChoice
import it.dvgarage.obd.service.Phase
import it.dvgarage.obd.service.UiState
import java.text.DateFormat
import java.util.Date

@Composable
fun MainScreen(
    state: UiState,
    serverUrl: String,
    deviceKey: String,
    selected: AdapterChoice?,
    choices: List<AdapterChoice>,
    scanning: Boolean,
    message: String?,
    running: Boolean,
    onServerUrl: (String) -> Unit,
    onDeviceKey: (String) -> Unit,
    onSelect: (AdapterChoice) -> Unit,
    onRefreshPaired: () -> Unit,
    onScan: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    MaterialTheme {
        Surface(Modifier.fillMaxWidth()) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("DvGarage OBD", style = MaterialTheme.typography.headlineSmall)

                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = onServerUrl,
                    label = { Text("Server URL (https://…)") },
                    singleLine = true,
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = deviceKey,
                    onValueChange = onDeviceKey,
                    label = { Text("Device key") },
                    singleLine = true,
                    enabled = !running,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("OBD adapter", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRefreshPaired, enabled = !running) { Text("Paired devices") }
                    OutlinedButton(onClick = onScan, enabled = !running && !scanning) {
                        Text(if (scanning) "Scanning…" else "Scan BLE")
                    }
                }
                val options = if (selected != null && choices.none { it.address == selected.address }) {
                    listOf(selected) + choices
                } else {
                    choices
                }
                options.forEach { choice ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !running) { onSelect(choice) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = choice.address == selected?.address, onClick = null, enabled = !running)
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(choice.name)
                            Text(
                                (if (choice.ble) "BLE · " else "Classic · ") + choice.address,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                if (running) {
                    Button(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Stop") }
                } else {
                    Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("Start") }
                }

                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Status(state)
            }
        }
    }
}

@Composable
private fun Status(state: UiState) {
    val label = when (state.phase) {
        Phase.STOPPED -> "Stopped"
        Phase.CONNECTING -> "Connecting"
        Phase.ONLINE -> "Online"
        Phase.ERROR -> "Error"
    }
    Text("Status: $label", style = MaterialTheme.typography.titleMedium)
    if (state.message.isNotBlank()) {
        Text(state.message, color = if (state.phase == Phase.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
    state.lastSyncMs?.let { Text("Last sync: ${DateFormat.getDateTimeInstance().format(Date(it))}") }
    state.snapshot?.let { snapshot ->
        snapshot.vin?.let { Text("VIN: $it") }
        Text("Check engine light: ${if (snapshot.milOn) "on" else "off"}")
        if (snapshot.dtcs.isEmpty()) {
            Text("No fault codes")
        } else {
            snapshot.dtcs.forEach { Text("${it.code}  (${it.kind.wire})") }
        }
    }
}

package it.dvgarage.obd.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import it.dvgarage.obd.VehicleLink
import it.dvgarage.obd.server.VehicleInfo
import it.dvgarage.obd.service.Phase
import it.dvgarage.obd.service.UiState
import java.text.DateFormat
import java.util.Date

@Composable
fun VehicleTab(
    state: UiState,
    linked: VehicleLink?,
    vehicles: List<VehicleInfo>,
    newVehicleName: String,
    message: String?,
    busy: Boolean,
    running: Boolean,
    adapterPicker: @Composable () -> Unit,
    onNewVehicleName: (String) -> Unit,
    onCreateVehicle: () -> Unit,
    onLinkVehicle: (VehicleInfo) -> Unit,
    onReloadVehicles: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Veicolo", style = MaterialTheme.typography.titleMedium)
        Text(linked?.let { "Collegato a: ${it.name}" } ?: "Nessun veicolo collegato a questo telefono")

        if (!running) {
            vehicles.forEach { vehicle ->
                OutlinedButton(
                    onClick = { onLinkVehicle(vehicle) },
                    enabled = !busy && vehicle.id != linked?.id,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (vehicle.id == linked?.id) "${vehicle.name} (in uso)" else "Usa ${vehicle.name}") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = newVehicleName,
                    onValueChange = onNewVehicleName,
                    label = { Text("Nuovo veicolo") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = onCreateVehicle, enabled = !busy && newVehicleName.isNotBlank()) { Text("Aggiungi") }
            }
            OutlinedButton(onClick = onReloadVehicles, enabled = !busy) { Text("Aggiorna elenco") }
            adapterPicker()
        }

        if (running) {
            Button(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Ferma monitoraggio") }
        } else {
            Button(onClick = onStart, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Avvia monitoraggio") }
        }

        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        MonitorStatus(state)
    }
}

@Composable
private fun MonitorStatus(state: UiState) {
    val label = when (state.phase) {
        Phase.STOPPED -> "Fermo"
        Phase.CONNECTING -> "Connessione"
        Phase.ONLINE -> "Online"
        Phase.ERROR -> "Errore"
    }
    Text("Stato: $label", style = MaterialTheme.typography.titleMedium)
    if (state.message.isNotBlank()) {
        Text(
            state.message,
            color = if (state.phase == Phase.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
    state.lastSyncMs?.let { Text("Ultimo invio: ${DateFormat.getDateTimeInstance().format(Date(it))}") }
    state.snapshot?.let { SnapshotView(it) }
}

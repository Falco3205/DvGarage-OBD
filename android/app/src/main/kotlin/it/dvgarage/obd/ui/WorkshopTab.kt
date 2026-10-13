package it.dvgarage.obd.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import it.dvgarage.obd.server.CustomerInfo
import it.dvgarage.obd.service.WorkshopState

@Composable
fun WorkshopTab(
    state: WorkshopState,
    customers: List<CustomerInfo>,
    customerId: Long?,
    label: String,
    monitorRunning: Boolean,
    saveMessage: String?,
    savingReport: Boolean,
    adapterPicker: @Composable () -> Unit,
    onCustomer: (Long?) -> Unit,
    onLabel: (String) -> Unit,
    onRead: () -> Unit,
    onClear: () -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
) {
    val locked = state.busy || monitorRunning
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Officina", style = MaterialTheme.typography.titleMedium)
        Text("Lettura diretta dall'adattatore, senza bisogno del cliente.", style = MaterialTheme.typography.bodySmall)
        if (monitorRunning) {
            Text("Ferma il monitoraggio del veicolo per usare l'officina.", color = MaterialTheme.colorScheme.error)
        }

        adapterPicker()

        Button(onClick = onRead, enabled = !locked, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.busy) "Attendere…" else "Leggi errori")
        }

        if (state.message.isNotBlank()) {
            Text(state.message, color = if (state.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        }

        state.snapshot?.let { snapshot ->
            SnapshotView(snapshot)

            Button(
                onClick = onClear,
                enabled = !locked && snapshot.dtcs.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Cancella errori") }

            Text("Salva sul server", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = label,
                onValueChange = onLabel,
                label = { Text("Descrizione") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            CustomerPicker(customers, customerId, enabled = !savingReport, onSelect = onCustomer)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSave, enabled = !locked && !savingReport && label.isNotBlank()) { Text("Salva lettura") }
                OutlinedButton(onClick = onReset, enabled = !locked) { Text("Nuova lettura") }
            }
            saveMessage?.let { Text(it) }
        }
    }
}

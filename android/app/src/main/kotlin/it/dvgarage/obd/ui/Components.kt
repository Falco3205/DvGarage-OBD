package it.dvgarage.obd.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import it.dvgarage.obd.AdapterChoice
import it.dvgarage.obd.core.Snapshot
import it.dvgarage.obd.server.CustomerInfo

@Composable
fun AdapterPicker(
    choices: List<AdapterChoice>,
    selected: AdapterChoice?,
    scanning: Boolean,
    enabled: Boolean,
    onSelect: (AdapterChoice) -> Unit,
    onPaired: () -> Unit,
    onScan: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Adattatore OBD", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPaired, enabled = enabled) { Text("Dispositivi associati") }
            OutlinedButton(onClick = onScan, enabled = enabled && !scanning) {
                Text(if (scanning) "Ricerca…" else "Cerca BLE")
            }
        }
        val options = if (selected != null && choices.none { it.address == selected.address }) listOf(selected) + choices else choices
        if (options.isEmpty()) {
            Text(
                "Associa l'adattatore dalle impostazioni Bluetooth di Android (PIN 1234 o 0000), poi premi \"Dispositivi associati\".",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        options.forEach { choice ->
            Row(
                Modifier.fillMaxWidth().clickable(enabled = enabled) { onSelect(choice) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = choice.address == selected?.address, onClick = null, enabled = enabled)
                Column(Modifier.padding(start = 8.dp)) {
                    Text(choice.name)
                    Text(
                        (if (choice.ble) "BLE · " else "Classic · ") + choice.address,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
fun SnapshotView(snapshot: Snapshot) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        snapshot.vin?.let { Text("VIN: $it") }
        Text("Spia motore: ${if (snapshot.milOn) "accesa" else "spenta"}")
        if (snapshot.dtcs.isEmpty()) {
            Text("Nessun errore presente")
        } else {
            snapshot.dtcs.forEach { Text("${it.code}  (${kindLabel(it.kind.wire)})") }
        }
    }
}

private fun kindLabel(wire: String) = when (wire) {
    "stored" -> "memorizzato"
    "pending" -> "in attesa"
    else -> "permanente"
}

@Composable
fun CustomerPicker(
    customers: List<CustomerInfo>,
    selectedId: Long?,
    enabled: Boolean,
    onSelect: (Long?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val current = customers.firstOrNull { it.id == selectedId }
    Box {
        OutlinedButton(onClick = { open = true }, enabled = enabled) {
            Text(current?.let { "${it.displayName} (${it.username})" } ?: "Senza cliente")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Senza cliente") }, onClick = { onSelect(null); open = false })
            customers.forEach { customer ->
                DropdownMenuItem(
                    text = { Text("${customer.displayName} (${customer.username})") },
                    onClick = { onSelect(customer.id); open = false },
                )
            }
        }
    }
}

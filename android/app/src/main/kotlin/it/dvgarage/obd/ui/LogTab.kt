package it.dvgarage.obd.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LogTab(lines: List<String>, onShare: () -> Unit, onClear: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Log comunicazione", style = MaterialTheme.typography.titleMedium)
        Text(
            "Comandi e risposte scambiati con l'adattatore: utile per diagnosticare problemi con adattatori particolari.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onShare, enabled = lines.isNotEmpty()) { Text("Condividi") }
            OutlinedButton(onClick = onClear, enabled = lines.isNotEmpty()) { Text("Svuota") }
        }
        lines.asReversed().forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
    }
}

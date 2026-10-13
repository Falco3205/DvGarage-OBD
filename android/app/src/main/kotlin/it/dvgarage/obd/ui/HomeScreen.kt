package it.dvgarage.obd.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import it.dvgarage.obd.Account

enum class HomeTab(val title: String) {
    VEHICLE("Veicolo"),
    WORKSHOP("Officina"),
    LOG("Log"),
}

@Composable
fun HomeScreen(
    account: Account,
    tab: HomeTab,
    onTab: (HomeTab) -> Unit,
    onLogout: () -> Unit,
    content: @Composable () -> Unit,
) {
    val tabs = HomeTab.entries.filter { it != HomeTab.WORKSHOP || account.isAdmin }
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("DvGarage OBD", style = MaterialTheme.typography.titleLarge)
                        Text(account.displayName, style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = onLogout) { Text("Esci") }
                }
                TabRow(selectedTabIndex = tabs.indexOf(tab).coerceAtLeast(0)) {
                    tabs.forEach { Tab(selected = it == tab, onClick = { onTab(it) }, text = { Text(it.title) }) }
                }
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { content() }
            }
        }
    }
}

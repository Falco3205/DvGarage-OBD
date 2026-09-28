package it.dvgarage.obd.service

import it.dvgarage.obd.core.Snapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class Phase { STOPPED, CONNECTING, ONLINE, ERROR }

data class UiState(
    val phase: Phase = Phase.STOPPED,
    val message: String = "",
    val snapshot: Snapshot? = null,
    val lastSyncMs: Long? = null,
)

object AppState {
    private val mutable = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = mutable

    fun update(phase: Phase, message: String, snapshot: Snapshot? = null, lastSyncMs: Long? = null) {
        mutable.value = mutable.value.copy(
            phase = phase,
            message = message,
            snapshot = snapshot ?: mutable.value.snapshot,
            lastSyncMs = lastSyncMs ?: mutable.value.lastSyncMs,
        )
    }
}

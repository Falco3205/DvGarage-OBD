package it.dvgarage.obd.service

import android.content.Context
import it.dvgarage.obd.AdapterChoice
import it.dvgarage.obd.bluetooth.Adapters
import it.dvgarage.obd.core.Elm327Client
import it.dvgarage.obd.core.ObdException
import it.dvgarage.obd.core.ObdSession
import it.dvgarage.obd.core.Snapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.util.concurrent.Executors

data class WorkshopState(
    val busy: Boolean = false,
    val message: String = "",
    val isError: Boolean = false,
    val snapshot: Snapshot? = null,
    val cleared: Boolean = false,
)

class WorkshopRunner private constructor(context: Context) {
    private val adapters = Adapters(context.applicationContext)
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "obd-workshop") }
    private val mutable = MutableStateFlow(WorkshopState())

    val state: StateFlow<WorkshopState> = mutable

    fun read(adapter: AdapterChoice) = run(adapter, "Lettura in corso…") { session ->
        val snapshot = session.readSnapshot()
        Outcome(snapshot, cleared = false, message = describe(snapshot))
    }

    fun clear(adapter: AdapterChoice) = run(adapter, "Cancellazione in corso…") { session ->
        if (!session.clearCodes()) {
            throw ObdException("La centralina ha rifiutato la cancellazione (quadro acceso, motore spento)")
        }
        val snapshot = session.readSnapshot()
        val message = if (snapshot.dtcs.isEmpty()) {
            "Errori cancellati"
        } else {
            "Cancellati, ma ${snapshot.dtcs.size} errore/i risultano ancora presenti"
        }
        Outcome(snapshot, cleared = true, message = message)
    }

    private class Outcome(val snapshot: Snapshot, val cleared: Boolean, val message: String)

    private fun describe(snapshot: Snapshot) =
        if (snapshot.dtcs.isEmpty()) "Nessun errore presente" else "${snapshot.dtcs.size} errore/i letti"

    private fun run(adapter: AdapterChoice, progress: String, action: (ObdSession) -> Outcome) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, message = progress, isError = false)
        executor.execute {
            val transport = adapters.createTransport(adapter)
            try {
                transport.open()
                val session = ObdSession(Elm327Client(transport))
                session.connect()
                val outcome = action(session)
                mutable.value = WorkshopState(
                    busy = false,
                    message = outcome.message,
                    snapshot = outcome.snapshot,
                    cleared = outcome.cleared || mutable.value.cleared,
                )
            } catch (e: ObdException) {
                fail(e.message)
            } catch (e: IOException) {
                fail(e.message)
            } catch (e: SecurityException) {
                fail("Permesso Bluetooth mancante")
            } catch (e: RuntimeException) {
                fail(e.message ?: e.javaClass.simpleName)
            } finally {
                runCatching { transport.close() }
            }
        }
    }

    private fun fail(message: String?) {
        mutable.value = mutable.value.copy(busy = false, message = message ?: "Errore sconosciuto", isError = true)
    }

    fun reset() {
        if (!mutable.value.busy) mutable.value = WorkshopState()
    }

    companion object {
        @Volatile private var instance: WorkshopRunner? = null

        fun get(context: Context): WorkshopRunner =
            instance ?: synchronized(this) {
                instance ?: WorkshopRunner(context).also { instance = it }
            }
    }
}

package it.dvgarage.obd.service

import android.content.Context
import it.dvgarage.obd.AdapterChoice
import it.dvgarage.obd.bluetooth.Adapters
import it.dvgarage.obd.core.Elm327Client
import it.dvgarage.obd.core.ObdException
import it.dvgarage.obd.core.ObdSession
import it.dvgarage.obd.core.ObdTransport
import it.dvgarage.obd.server.ServerClient
import it.dvgarage.obd.server.ServerCommand
import it.dvgarage.obd.server.ApiException
import java.io.IOException

private class CommandRefused(message: String) : Exception(message)

class ObdRunner(
    context: Context,
    serverUrl: String,
    deviceKey: String,
    private val adapter: AdapterChoice,
) {
    private val adapters = Adapters(context)
    private val server = ServerClient(serverUrl, deviceKey)
    private val thread = Thread(::loop, "obd-runner")

    @Volatile private var running = true
    @Volatile private var transport: ObdTransport? = null

    fun start() {
        AppState.update(Phase.CONNECTING, "Connessione a ${adapter.name}")
        thread.start()
    }

    fun stop() {
        running = false
        server.abort()
        transport?.close()
        thread.interrupt()
    }

    private fun loop() {
        var backoffMs = INITIAL_BACKOFF_MS
        while (running) {
            try {
                runSession()
                backoffMs = INITIAL_BACKOFF_MS
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                if (!running) break
                AppState.update(Phase.ERROR, e.message ?: e.javaClass.simpleName)
                if (!pause(backoffMs)) break
                backoffMs = minOf(backoffMs * 2, MAX_BACKOFF_MS)
            }
        }
        AppState.update(Phase.STOPPED, "Fermato")
    }

    private fun runSession() {
        AppState.update(Phase.CONNECTING, "Connessione a ${adapter.name}")
        val link = adapters.createTransport(adapter)
        transport = link
        try {
            link.open()
            val obd = ObdSession(Elm327Client(link))
            obd.connect()
            syncSnapshot(obd)
            serve(obd)
        } finally {
            link.close()
            transport = null
        }
    }

    private fun serve(obd: ObdSession) {
        var lastSync = System.currentTimeMillis()
        while (running) {
            val command = try {
                server.nextCommand(POLL_SECONDS)
            } catch (e: ApiException) {
                if (e.status == 401) throw IOException("Veicolo non più collegato: accedi di nuovo e ricollega il veicolo")
                reportServerProblem(e)
                continue
            } catch (e: IOException) {
                if (!running) return
                reportServerProblem(e)
                continue
            }
            if (command != null) {
                when (command.type) {
                    "clear_dtc" -> execute(command) { clear(obd) }
                    "rescan" -> execute(command) { syncSnapshot(obd); "Rilettura completata" }
                    else -> server.reportResult(command.id, false, "Comando non supportato")
                }
            }
            if (System.currentTimeMillis() - lastSync >= RESCAN_INTERVAL_MS) {
                syncSnapshot(obd)
                lastSync = System.currentTimeMillis()
            }
        }
    }

    private fun clear(obd: ObdSession): String {
        if (!obd.clearCodes()) throw CommandRefused("La centralina ha rifiutato la cancellazione (quadro acceso, motore spento)")
        val remaining = syncSnapshot(obd)
        return if (remaining == 0) "Errori cancellati" else "Cancellati, ma $remaining errore/i risultano ancora presenti"
    }

    private fun execute(command: ServerCommand, action: () -> String) {
        try {
            server.reportResult(command.id, true, action())
        } catch (e: CommandRefused) {
            server.reportResult(command.id, false, e.message.orEmpty())
        } catch (e: ObdException) {
            server.reportResult(command.id, false, e.message ?: "Errore adattatore")
            throw e
        }
    }

    private fun syncSnapshot(obd: ObdSession): Int {
        val snapshot = obd.readSnapshot()
        server.uploadScan(snapshot)
        AppState.update(Phase.ONLINE, "Online", snapshot, System.currentTimeMillis())
        return snapshot.dtcs.size
    }

    private fun reportServerProblem(e: IOException) {
        AppState.update(Phase.ONLINE, "Server non raggiungibile: ${e.message}")
        pause(SERVER_RETRY_MS)
    }

    private fun pause(ms: Long): Boolean =
        try {
            Thread.sleep(ms)
            running
        } catch (_: InterruptedException) {
            false
        }

    private companion object {
        const val POLL_SECONDS = 20
        const val RESCAN_INTERVAL_MS = 5 * 60_000L
        const val INITIAL_BACKOFF_MS = 3_000L
        const val MAX_BACKOFF_MS = 60_000L
        const val SERVER_RETRY_MS = 5_000L
    }
}

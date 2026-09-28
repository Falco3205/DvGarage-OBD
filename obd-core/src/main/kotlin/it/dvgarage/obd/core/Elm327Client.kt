package it.dvgarage.obd.core

class Elm327Client(private val transport: ObdTransport) {

    fun initialize() {
        transport.exchange("ATZ", RESET_TIMEOUT_MS)
        for (command in SETUP_COMMANDS) {
            val reply = transport.exchange(command, COMMAND_TIMEOUT_MS)
            if (!reply.uppercase().contains("OK")) {
                throw ObdProtocolException("Adapter rejected $command: ${reply.trim()}")
            }
        }
    }

    fun request(command: String, timeoutMs: Long = REQUEST_TIMEOUT_MS): Reply =
        ObdParser.parse(transport.exchange(command, timeoutMs))

    companion object {
        private val SETUP_COMMANDS = listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")
        private const val RESET_TIMEOUT_MS = 6_000L
        private const val COMMAND_TIMEOUT_MS = 3_000L
        const val REQUEST_TIMEOUT_MS = 15_000L
    }
}

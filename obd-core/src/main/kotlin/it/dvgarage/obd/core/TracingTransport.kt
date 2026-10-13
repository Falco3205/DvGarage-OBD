package it.dvgarage.obd.core

class TracingTransport(
    private val delegate: ObdTransport,
    private val sink: (String) -> Unit,
) : ObdTransport {

    override fun open() {
        sink("open")
        try {
            delegate.open()
            sink("opened")
        } catch (e: Exception) {
            sink("open failed: ${e.message}")
            throw e
        }
    }

    override fun exchange(command: String, timeoutMs: Long): String {
        sink("> $command")
        try {
            val reply = delegate.exchange(command, timeoutMs)
            sink("< ${reply.trim().replace('\r', '|').replace('\n', '|')}")
            return reply
        } catch (e: Exception) {
            sink("! ${e.message}")
            throw e
        }
    }

    override fun close() {
        sink("close")
        delegate.close()
    }
}

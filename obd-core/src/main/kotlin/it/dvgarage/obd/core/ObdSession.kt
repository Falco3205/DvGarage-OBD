package it.dvgarage.obd.core

class ObdSession(private val client: Elm327Client) {

    fun connect() = client.initialize()

    fun readSnapshot(): Snapshot {
        val status = messagesOrThrow(client.request("0101"), "The vehicle did not answer. Turn the ignition on.")
            .let { ObdParser.decodeMonitorStatus(it) }
            ?: throw ObdProtocolException("Vehicle returned no monitor status")

        val stored = ObdParser.decodeDtcs(requiredMessages("03"), 0x43, DtcKind.STORED)
        val pending = ObdParser.decodeDtcs(optionalMessages("07"), 0x47, DtcKind.PENDING)
        val permanent = ObdParser.decodeDtcs(optionalMessages("0A"), 0x4A, DtcKind.PERMANENT)
        val vin = ObdParser.decodeVin(optionalMessages("0902"))

        return Snapshot(vin = vin, milOn = status.milOn, dtcs = stored + pending + permanent)
    }

    fun clearCodes(): Boolean = when (val reply = client.request("04")) {
        is Reply.Messages -> ObdParser.isPositiveResponse(reply.messages, 0x44)
        else -> false
    }

    private fun requiredMessages(command: String): List<ByteArray> = when (val reply = client.request(command)) {
        is Reply.Messages -> reply.messages
        is Reply.NoData -> emptyList()
        is Reply.Failure -> throw ObdProtocolException(reply.message)
    }

    private fun optionalMessages(command: String): List<ByteArray> =
        (client.request(command) as? Reply.Messages)?.messages.orEmpty()

    private fun messagesOrThrow(reply: Reply, noDataMessage: String): List<ByteArray> = when (reply) {
        is Reply.Messages -> reply.messages
        is Reply.NoData -> throw ObdProtocolException(noDataMessage)
        is Reply.Failure -> throw ObdProtocolException(reply.message)
    }
}

package it.dvgarage.obd.core

sealed interface Reply {
    class Messages(val messages: List<ByteArray>) : Reply
    data object NoData : Reply
    class Failure(val message: String) : Reply
}

object ObdParser {
    private val hexLine = Regex("^[0-9A-F]+$")
    private val indexedLine = Regex("^[0-9A-F]:([0-9A-F]*)$")
    private val lengthLine = Regex("^[0-9A-F]{3}$")
    private val errorMarkers = listOf("UNABLETOCONNECT", "ERROR", "STOPPED", "BUFFERFULL", "LVRESET")
    private val vinPattern = Regex("^[A-HJ-NPR-Z0-9]{17}$")

    fun parse(raw: String): Reply {
        val lines = raw.uppercase()
            .split('\r', '\n')
            .map { it.replace(" ", "").trim() }
            .filter { it.isNotEmpty() && it != "SEARCHING..." && !isBusInitOk(it) }

        var sawNoData = false
        val direct = mutableListOf<String>()
        val frames = mutableListOf<String>()
        var declaredLength: Int? = null

        for (line in lines) {
            when {
                line == "NODATA" -> sawNoData = true
                line == "?" || errorMarkers.any { line.contains(it) } ->
                    return Reply.Failure(line)
                indexedLine.matches(line) -> frames += indexedLine.matchEntire(line)!!.groupValues[1]
                lengthLine.matches(line) && frames.isEmpty() && declaredLength == null && lines.any { indexedLine.matches(it) } ->
                    declaredLength = line.toInt(16)
                hexLine.matches(line) && line.length % 2 == 0 -> direct += line
                else -> return Reply.Failure("Unexpected reply: $line")
            }
        }

        val messages = mutableListOf<ByteArray>()
        if (frames.isNotEmpty()) {
            val bytes = hexToBytes(frames.joinToString(""))
            messages += declaredLength?.let { bytes.copyOf(minOf(it, bytes.size)) } ?: bytes
        }
        direct.mapTo(messages) { hexToBytes(it) }

        return when {
            messages.isNotEmpty() -> Reply.Messages(messages)
            sawNoData -> Reply.NoData
            else -> Reply.Failure("Empty reply")
        }
    }

    fun decodeDtcs(messages: List<ByteArray>, responseId: Int, kind: DtcKind): List<Dtc> {
        val codes = LinkedHashSet<String>()
        for (message in messages) {
            if (message.isEmpty() || message[0].toInt() and 0xFF != responseId) continue
            val body = message.copyOfRange(1, message.size)
            val start = if (body.size % 2 == 1) 1 else 0
            var i = start
            while (i + 1 < body.size) {
                val hi = body[i].toInt() and 0xFF
                val lo = body[i + 1].toInt() and 0xFF
                if (hi != 0 || lo != 0) codes += formatDtc(hi, lo)
                i += 2
            }
        }
        return codes.map { Dtc(it, kind) }
    }

    fun decodeMonitorStatus(messages: List<ByteArray>): MonitorStatus? {
        val statuses = messages.filter { it.size >= 3 && it[0].toInt() == 0x41 && it[1].toInt() == 0x01 }
        if (statuses.isEmpty()) return null
        return MonitorStatus(
            milOn = statuses.any { it[2].toInt() and 0x80 != 0 },
            dtcCount = statuses.maxOf { it[2].toInt() and 0x7F },
        )
    }

    fun decodeVin(messages: List<ByteArray>): String? {
        val vin = messages
            .filter { it.size > 3 && it[0].toInt() == 0x49 && it[1].toInt() == 0x02 }
            .sortedBy { it[2].toInt() and 0xFF }
            .flatMap { it.drop(3) }
            .map { it.toInt() and 0xFF }
            .filter { it in 0x30..0x5A }
            .map { it.toChar() }
            .joinToString("")
        return vin.takeIf { vinPattern.matches(it) }
    }

    fun isPositiveResponse(messages: List<ByteArray>, responseId: Int): Boolean =
        messages.any { it.isNotEmpty() && it[0].toInt() and 0xFF == responseId }

    private fun formatDtc(hi: Int, lo: Int): String {
        val system = "PCBU"[hi shr 6]
        val digit = (hi shr 4) and 0x3
        return "$system$digit${(hi and 0xF).toString(16)}${lo.toString(16).padStart(2, '0')}".uppercase()
    }

    private fun isBusInitOk(line: String) = line.startsWith("BUSINIT") && line.endsWith("OK")

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

data class MonitorStatus(val milOn: Boolean, val dtcCount: Int)

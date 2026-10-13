package it.dvgarage.obd.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class ScriptedTransport(private val replies: Map<String, String>) : PromptTransport() {
    val sent = mutableListOf<String>()

    override fun open() = Unit
    override fun close() = Unit

    override fun write(data: ByteArray) {
        val command = String(data, Charsets.US_ASCII).trim()
        sent += command
        val reply = replies[command] ?: "?"
        Thread { onData("$reply\r\r>".toByteArray(Charsets.US_ASCII)) }.start()
    }
}

class ObdSessionTest {

    private val healthy = mapOf(
        "ATZ" to "ELM327 v1.5", "ATE0" to "OK", "ATL0" to "OK", "ATS0" to "OK", "ATH0" to "OK", "ATSP0" to "OK",
        "0101" to "SEARCHING...\r41 01 82 07 E5 00",
        "03" to "43 02 01 33 04 20 00 00",
        "07" to "47 01 01 71 00 00 00 00",
        "0A" to "NO DATA",
        "0902" to "014\r0: 49 02 01 5A 46 41\r1: 31 36 39 30 30 30 30\r2: 31 32 33 34 35 36 37",
        "04" to "44",
    )

    private fun session(replies: Map<String, String>): Pair<ObdSession, ScriptedTransport> {
        val transport = ScriptedTransport(replies)
        return ObdSession(Elm327Client(transport)) to transport
    }

    @Test
    fun `reads a full snapshot`() {
        val (session, transport) = session(healthy)
        session.connect()
        val snapshot = session.readSnapshot()

        assertEquals("ZFA16900001234567", snapshot.vin)
        assertTrue(snapshot.milOn)
        assertEquals(
            listOf(Dtc("P0133", DtcKind.STORED), Dtc("P0420", DtcKind.STORED), Dtc("P0171", DtcKind.PENDING)),
            snapshot.dtcs,
        )
        assertEquals(listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0"), transport.sent.take(6))
    }

    @Test
    fun `clears codes only on positive response`() {
        assertTrue(session(healthy).first.clearCodes())
        assertFalse(session(healthy + ("04" to "7F 04 22")).first.clearCodes())
        assertFalse(session(healthy + ("04" to "NO DATA")).first.clearCodes())
    }

    @Test
    fun `fails clearly when the vehicle does not answer`() {
        val (session, _) = session(healthy + ("0101" to "UNABLE TO CONNECT"))
        assertFailsWith<ObdProtocolException> { session.readSnapshot() }
        val (silent, _) = session(healthy + ("0101" to "NO DATA"))
        assertFailsWith<ObdProtocolException> { silent.readSnapshot() }
    }

    @Test
    fun `initialisation fails when the adapter rejects a setup command`() {
        val (session, _) = session(healthy + ("ATH0" to "?"))
        assertFailsWith<ObdProtocolException> { session.connect() }
    }

    @Test
    fun `times out when the adapter stays silent`() {
        val transport = object : PromptTransport() {
            override fun open() = Unit
            override fun close() = Unit
            override fun write(data: ByteArray) = Unit
        }
        assertFailsWith<ObdTimeoutException> { transport.exchange("ATZ", 100) }
    }
}

class TracingTransportTest {
    @Test
    fun `records commands and replies`() {
        val log = mutableListOf<String>()
        val inner = object : ObdTransport {
            override fun open() = Unit
            override fun close() = Unit
            override fun exchange(command: String, timeoutMs: Long) = "41 00 BE 3E\r"
        }
        TracingTransport(inner) { log += it }.exchange("0100", 100)
        assertEquals(listOf("> 0100", "< 41 00 BE 3E"), log)
    }
}

package it.dvgarage.obd.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ObdParserTest {

    private fun messages(raw: String) = assertIs<Reply.Messages>(ObdParser.parse(raw)).messages

    private fun codes(raw: String, id: Int, kind: DtcKind = DtcKind.STORED) =
        ObdParser.decodeDtcs(messages(raw), id, kind).map { it.code }

    @Test
    fun `decodes CAN single frame with count byte`() {
        assertEquals(listOf("P0133", "P0420"), codes("43 02 01 33 04 20 00 00\r\r", 0x43))
    }

    @Test
    fun `decodes legacy protocol lines without count byte`() {
        assertEquals(listOf("P0133"), codes("43 01 33 00 00 00 00\r\r", 0x43))
    }

    @Test
    fun `decodes all four systems`() {
        assertEquals(listOf("P0100", "C0123", "B1234", "U2345"), codes("43 04 01 00 41 23 92 34 E3 45", 0x43))
    }

    @Test
    fun `reports no codes when the ECU answers with an empty list`() {
        assertEquals(emptyList(), codes("43 00\r", 0x43))
        assertEquals(emptyList(), codes("43 00 00 00 00 00 00\r", 0x43))
    }

    @Test
    fun `assembles CAN multi frame replies and honours declared length`() {
        val raw = "00E\r0: 43 06 01 33 04 20\r1: 01 71 03 00 00 00\r\r"
        assertEquals(listOf("P0133", "P0420", "P0171", "P0300"), codes(raw, 0x43))
    }

    @Test
    fun `keeps multi frame replies of different ECUs apart`() {
        val raw = "00A\r0: 43 04 01 33 04 20\r1: 00 00 00 00 00 00\r008\r0: 43 03 01 71 03 00\r1: 00 00 00 00 00 00\r"
        val parsed = messages(raw)
        assertEquals(2, parsed.size)
        assertEquals(listOf("P0133", "P0420", "P0171", "P0300"), codes(raw, 0x43))
    }

    @Test
    fun `merges the same code reported by two ECUs`() {
        assertEquals(listOf("P0300"), codes("43 01 03 00\r43 01 03 00\r", 0x43))
    }

    @Test
    fun `ignores searching banner and bus init lines`() {
        assertEquals(listOf("P0133"), codes("SEARCHING...\rBUS INIT: OK\r43 01 01 33\r", 0x43))
    }

    @Test
    fun `negative response yields no codes`() {
        assertEquals(emptyList(), codes("7F 0A 11\r", 0x4A, DtcKind.PERMANENT))
    }

    @Test
    fun `classifies NO DATA and adapter errors`() {
        assertEquals(Reply.NoData, ObdParser.parse("NO DATA\r"))
        assertIs<Reply.Failure>(ObdParser.parse("UNABLE TO CONNECT\r"))
        assertIs<Reply.Failure>(ObdParser.parse("CAN ERROR\r"))
        assertIs<Reply.Failure>(ObdParser.parse("BUS INIT: ...ERROR\r"))
        assertIs<Reply.Failure>(ObdParser.parse("?\r"))
        assertIs<Reply.Failure>(ObdParser.parse("garbage!\r"))
        assertIs<Reply.Failure>(ObdParser.parse(""))
    }

    @Test
    fun `decodes monitor status`() {
        val status = ObdParser.decodeMonitorStatus(messages("41 01 83 07 E5 00\r"))
        assertEquals(MonitorStatus(milOn = true, dtcCount = 3), status)
        assertEquals(false, ObdParser.decodeMonitorStatus(messages("41 01 00 07 E5 00\r"))?.milOn)
        assertNull(ObdParser.decodeMonitorStatus(messages("7F 01 12\r")))
    }

    @Test
    fun `decodes VIN from CAN multi frame reply`() {
        val raw = "014\r0: 49 02 01 5A 46 41\r1: 31 36 39 30 30 30 30\r2: 31 32 33 34 35 36 37\r"
        assertEquals("ZFA16900001234567", ObdParser.decodeVin(messages(raw)))
    }

    @Test
    fun `decodes VIN from legacy multi line reply`() {
        val raw = "49 02 01 00 00 00 5A\r49 02 02 46 41 31 36\r49 02 03 39 30 30 30\r49 02 04 30 31 32 33\r49 02 05 34 35 36 37\r"
        assertEquals("ZFA16900001234567", ObdParser.decodeVin(messages(raw)))
    }

    @Test
    fun `rejects malformed VIN`() {
        assertNull(ObdParser.decodeVin(messages("49 02 01 41 42 43\r")))
    }

    @Test
    fun `recognises positive clear response`() {
        assertTrue(ObdParser.isPositiveResponse(messages("44\r"), 0x44))
        assertEquals(false, ObdParser.isPositiveResponse(messages("7F 04 22\r"), 0x44))
    }
}

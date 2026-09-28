package it.dvgarage.obd.core

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class StreamTransport(
    private val connect: () -> Pair<InputStream, OutputStream>,
    private val disconnect: () -> Unit = {},
) : PromptTransport() {

    @Volatile private var output: OutputStream? = null
    @Volatile private var reader: Thread? = null
    @Volatile private var closed = false

    override fun open() {
        val (input, out) = connect()
        output = out
        closed = false
        reader = Thread({ pump(input) }, "obd-reader").apply {
            isDaemon = true
            start()
        }
    }

    private fun pump(input: InputStream) {
        val chunk = ByteArray(256)
        try {
            while (!closed) {
                val read = input.read(chunk)
                if (read < 0) break
                if (read > 0) onData(chunk, read)
            }
        } catch (_: IOException) {
        }
    }

    override fun write(data: ByteArray) {
        val out = output ?: throw ObdProtocolException("Transport is not open")
        try {
            out.write(data)
            out.flush()
        } catch (e: IOException) {
            throw ObdProtocolException("Adapter connection lost: ${e.message}")
        }
    }

    override fun close() {
        closed = true
        try {
            output?.close()
        } catch (_: IOException) {
        }
        disconnect()
        reader?.interrupt()
        output = null
        reader = null
    }
}

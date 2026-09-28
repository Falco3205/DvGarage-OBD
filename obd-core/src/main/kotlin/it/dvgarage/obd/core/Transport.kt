package it.dvgarage.obd.core

import java.io.Closeable
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

interface ObdTransport : Closeable {
    fun open()
    fun exchange(command: String, timeoutMs: Long): String
}

abstract class PromptTransport : ObdTransport {
    private val lock = ReentrantLock()
    private val arrived = lock.newCondition()
    private val buffer = StringBuilder()

    protected abstract fun write(data: ByteArray)

    protected fun onData(bytes: ByteArray, length: Int = bytes.size) {
        lock.withLock {
            buffer.append(String(bytes, 0, length, Charsets.US_ASCII))
            arrived.signalAll()
        }
    }

    override fun exchange(command: String, timeoutMs: Long): String {
        lock.withLock { buffer.setLength(0) }
        write("$command\r".toByteArray(Charsets.US_ASCII))
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        lock.withLock {
            while (true) {
                val end = buffer.indexOf(PROMPT)
                if (end >= 0) {
                    val reply = buffer.substring(0, end)
                    buffer.delete(0, end + 1)
                    return reply
                }
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) throw ObdTimeoutException(command)
                arrived.awaitNanos(remaining)
            }
        }
    }

    private companion object {
        const val PROMPT = ">"
    }
}

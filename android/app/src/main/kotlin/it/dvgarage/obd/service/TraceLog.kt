package it.dvgarage.obd.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TraceLog {
    private const val MAX_LINES = 400
    private val mutable = MutableStateFlow<List<String>>(emptyList())
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    val lines: StateFlow<List<String>> = mutable

    @Synchronized
    fun add(message: String) {
        val line = "${clock.format(Date())} $message"
        mutable.value = (mutable.value + line).takeLast(MAX_LINES)
    }

    @Synchronized
    fun clear() {
        mutable.value = emptyList()
    }

    fun text(): String = mutable.value.joinToString("\n")
}

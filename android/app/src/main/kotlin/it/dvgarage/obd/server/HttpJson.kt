package it.dvgarage.obd.server

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class ApiException(val status: Int, message: String) : IOException(message)

class HttpJson(baseUrl: String) {
    private val base = baseUrl.trim().trimEnd('/')

    @Volatile private var active: HttpURLConnection? = null

    fun abort() {
        active?.disconnect()
    }

    fun request(
        method: String,
        path: String,
        bearer: String?,
        body: JSONObject? = null,
        readTimeoutMs: Int = READ_TIMEOUT_MS,
    ): JSONObject? {
        val connection = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = readTimeoutMs
            setRequestProperty("Accept", "application/json")
            if (bearer != null) setRequestProperty("Authorization", "Bearer $bearer")
        }
        active = connection
        try {
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_NO_CONTENT) return null
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (status !in 200..299) {
                val reason = runCatching { JSONObject(text).getString("error") }.getOrDefault("HTTP $status")
                throw ApiException(status, reason)
            }
            return if (text.isBlank()) null else JSONObject(text)
        } finally {
            active = null
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 15_000
    }
}

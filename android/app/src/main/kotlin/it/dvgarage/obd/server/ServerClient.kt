package it.dvgarage.obd.server

import it.dvgarage.obd.core.Snapshot
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class ServerException(val status: Int, message: String) : IOException(message)

data class ServerCommand(val id: Long, val type: String)

class ServerClient(baseUrl: String, private val deviceKey: String) {
    private val base = baseUrl.trim().trimEnd('/')

    @Volatile private var active: HttpURLConnection? = null

    fun uploadScan(snapshot: Snapshot) {
        val body = JSONObject()
            .put("vin", snapshot.vin ?: JSONObject.NULL)
            .put("milOn", snapshot.milOn)
            .put(
                "dtcs",
                JSONArray().also { array ->
                    snapshot.dtcs.forEach { array.put(JSONObject().put("code", it.code).put("kind", it.kind.wire)) }
                },
            )
        call("POST", "/api/device/scans", body, READ_TIMEOUT_MS)
    }

    fun nextCommand(waitSeconds: Int): ServerCommand? {
        val response = call("GET", "/api/device/commands/next?wait=$waitSeconds", null, (waitSeconds + 10) * 1000)
            ?: return null
        val command = response.getJSONObject("command")
        return ServerCommand(command.getLong("id"), command.getString("type"))
    }

    fun reportResult(commandId: Long, ok: Boolean, message: String) {
        val body = JSONObject().put("ok", ok).put("message", message)
        call("POST", "/api/device/commands/$commandId/result", body, READ_TIMEOUT_MS)
    }

    fun abort() {
        active?.disconnect()
    }

    private fun call(method: String, path: String, body: JSONObject?, readTimeoutMs: Int): JSONObject? {
        val connection = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = readTimeoutMs
            setRequestProperty("Authorization", "Bearer $deviceKey")
            setRequestProperty("Accept", "application/json")
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
                throw ServerException(status, reason)
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

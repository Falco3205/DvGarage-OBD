package it.dvgarage.obd.server

import it.dvgarage.obd.core.Snapshot
import org.json.JSONArray
import org.json.JSONObject

data class ServerCommand(val id: Long, val type: String)

fun Snapshot.toJson(): JSONObject = JSONObject()
    .put("vin", vin ?: JSONObject.NULL)
    .put("milOn", milOn)
    .put(
        "dtcs",
        JSONArray().also { array ->
            dtcs.forEach { array.put(JSONObject().put("code", it.code).put("kind", it.kind.wire)) }
        },
    )

class ServerClient(baseUrl: String, private val deviceKey: String) {
    private val http = HttpJson(baseUrl)

    fun uploadScan(snapshot: Snapshot) {
        http.request("POST", "/api/device/scans", deviceKey, snapshot.toJson())
    }

    fun nextCommand(waitSeconds: Int): ServerCommand? {
        val response = http.request("GET", "/api/device/commands/next?wait=$waitSeconds", deviceKey, null, (waitSeconds + 10) * 1000)
            ?: return null
        val command = response.getJSONObject("command")
        return ServerCommand(command.getLong("id"), command.getString("type"))
    }

    fun reportResult(commandId: Long, ok: Boolean, message: String) {
        http.request("POST", "/api/device/commands/$commandId/result", deviceKey, JSONObject().put("ok", ok).put("message", message))
    }

    fun abort() = http.abort()
}

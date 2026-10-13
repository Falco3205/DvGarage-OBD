package it.dvgarage.obd.server

import it.dvgarage.obd.VehicleLink
import it.dvgarage.obd.core.Snapshot
import org.json.JSONObject

data class LoginResult(val token: String, val username: String, val displayName: String, val role: String)

data class VehicleInfo(val id: Long, val name: String, val vin: String?)

data class CustomerInfo(val id: Long, val displayName: String, val username: String)

class UserApi(baseUrl: String, private val token: String) {
    private val http = HttpJson(baseUrl)

    fun vehicles(): List<VehicleInfo> {
        val devices = http.request("GET", "/api/me/devices", token)!!.getJSONArray("devices")
        return List(devices.length()) {
            val d = devices.getJSONObject(it)
            VehicleInfo(d.getLong("id"), d.getString("name"), if (d.isNull("vin")) null else d.getString("vin"))
        }
    }

    fun createVehicle(name: String): VehicleLink {
        val device = http.request("POST", "/api/me/devices", token, JSONObject().put("name", name))!!.getJSONObject("device")
        return VehicleLink(device.getLong("id"), device.getString("name"), device.getString("key"))
    }

    fun linkVehicle(id: Long): VehicleLink {
        val device = http.request("POST", "/api/devices/$id/key", token)!!.getJSONObject("device")
        return VehicleLink(device.getLong("id"), device.getString("name"), device.getString("key"))
    }

    fun customers(): List<CustomerInfo> {
        val users = http.request("GET", "/api/admin/users?role=customer", token)!!.getJSONArray("users")
        return List(users.length()) {
            val u = users.getJSONObject(it)
            CustomerInfo(u.getLong("id"), u.getString("displayName"), u.getString("username"))
        }
    }

    fun saveWorkshopRead(label: String, customerId: Long?, snapshot: Snapshot, cleared: Boolean) {
        val body = snapshot.toJson()
            .put("label", label)
            .put("cleared", cleared)
            .put("customerId", customerId ?: JSONObject.NULL)
        http.request("POST", "/api/admin/workshop/reads", token, body)
    }

    fun logout() {
        http.request("POST", "/api/auth/logout", token)
    }

    companion object {
        fun login(baseUrl: String, username: String, password: String): LoginResult {
            val response = HttpJson(baseUrl).request(
                "POST", "/api/auth/login", null,
                JSONObject().put("username", username).put("password", password),
            )!!
            val user = response.getJSONObject("user")
            return LoginResult(
                token = response.getString("token"),
                username = user.getString("username"),
                displayName = user.getString("displayName"),
                role = user.getString("role"),
            )
        }
    }
}

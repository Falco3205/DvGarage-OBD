import Foundation
import OBDCore

struct LoginResult {
    let token: String
    let username: String
    let displayName: String
    let role: String
}

struct VehicleInfo: Identifiable, Equatable {
    let id: Int
    let name: String
    let vin: String?
}

struct VehicleLink: Equatable {
    let id: Int
    let name: String
    let key: String
}

struct CustomerInfo: Identifiable, Equatable {
    let id: Int
    let displayName: String
    let username: String
}

struct ServerCommand {
    let id: Int
    let type: String
}

extension Snapshot {
    var json: [String: Any] {
        [
            "vin": vin as Any? ?? NSNull(),
            "milOn": milOn,
            "dtcs": dtcs.map { ["code": $0.code, "kind": $0.kind.rawValue] },
        ]
    }
}

private func parseVehicleLink(_ response: [String: Any]?) throws -> VehicleLink {
    guard let device = response?["device"] as? [String: Any],
          let id = device["id"] as? Int, let name = device["name"] as? String, let key = device["key"] as? String
    else { throw ApiError(status: 0, message: "Risposta del server non valida") }
    return VehicleLink(id: id, name: name, key: key)
}

struct UserApi {
    let http: HttpClient
    let token: String

    init(serverURL: String, token: String) {
        http = HttpClient(baseURL: serverURL)
        self.token = token
    }

    static func login(serverURL: String, username: String, password: String) async throws -> LoginResult {
        let response = try await HttpClient(baseURL: serverURL).request(
            "POST", "/api/auth/login", bearer: nil, body: ["username": username, "password": password]
        )
        guard let token = response?["token"] as? String,
              let user = response?["user"] as? [String: Any],
              let name = user["username"] as? String,
              let display = user["displayName"] as? String,
              let role = user["role"] as? String
        else { throw ApiError(status: 0, message: "Risposta del server non valida") }
        return LoginResult(token: token, username: name, displayName: display, role: role)
    }

    func vehicles() async throws -> [VehicleInfo] {
        let list = try await http.request("GET", "/api/me/devices", bearer: token)?["devices"] as? [[String: Any]] ?? []
        return list.compactMap {
            guard let id = $0["id"] as? Int, let name = $0["name"] as? String else { return nil }
            return VehicleInfo(id: id, name: name, vin: $0["vin"] as? String)
        }
    }

    func createVehicle(name: String) async throws -> VehicleLink {
        try parseVehicleLink(try await http.request("POST", "/api/me/devices", bearer: token, body: ["name": name]))
    }

    func linkVehicle(id: Int) async throws -> VehicleLink {
        try parseVehicleLink(try await http.request("POST", "/api/devices/\(id)/key", bearer: token))
    }

    func customers() async throws -> [CustomerInfo] {
        let list = try await http.request("GET", "/api/admin/users?role=customer", bearer: token)?["users"] as? [[String: Any]] ?? []
        return list.compactMap {
            guard let id = $0["id"] as? Int, let display = $0["displayName"] as? String,
                  let username = $0["username"] as? String else { return nil }
            return CustomerInfo(id: id, displayName: display, username: username)
        }
    }

    func saveWorkshopRead(label: String, customerId: Int?, snapshot: Snapshot, cleared: Bool) async throws {
        var body = snapshot.json
        body["label"] = label
        body["cleared"] = cleared
        body["customerId"] = customerId as Any? ?? NSNull()
        _ = try await http.request("POST", "/api/admin/workshop/reads", bearer: token, body: body)
    }

    func logout() async {
        _ = try? await http.request("POST", "/api/auth/logout", bearer: token)
    }
}

struct DeviceApi {
    let http: HttpClient
    let key: String

    init(serverURL: String, key: String) {
        http = HttpClient(baseURL: serverURL)
        self.key = key
    }

    func uploadScan(_ snapshot: Snapshot) async throws {
        _ = try await http.request("POST", "/api/device/scans", bearer: key, body: snapshot.json)
    }

    func nextCommand(waitSeconds: Int) async throws -> ServerCommand? {
        let response = try await http.request(
            "GET", "/api/device/commands/next?wait=\(waitSeconds)", bearer: key, timeout: TimeInterval(waitSeconds + 10)
        )
        guard let command = response?["command"] as? [String: Any],
              let id = command["id"] as? Int, let type = command["type"] as? String else { return nil }
        return ServerCommand(id: id, type: type)
    }

    func reportResult(commandId: Int, ok: Bool, message: String) async throws {
        _ = try await http.request(
            "POST", "/api/device/commands/\(commandId)/result", bearer: key, body: ["ok": ok, "message": message]
        )
    }
}

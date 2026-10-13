import Foundation
import OBDCore

struct Account: Equatable {
    let serverURL: String
    let token: String
    let username: String
    let displayName: String
    let role: String

    var isAdmin: Bool { role == "admin" }
}

@MainActor
final class AppModel: ObservableObject {
    @Published private(set) var account: Account?
    @Published private(set) var vehicle: VehicleLink?
    @Published var adapter: AdapterChoice? {
        didSet { saveAdapter() }
    }
    @Published private(set) var vehicles: [VehicleInfo] = []
    @Published private(set) var customers: [CustomerInfo] = []
    @Published var loginError: String?
    @Published var vehicleMessage: String?
    @Published private(set) var busy = false

    let ble = BleAdapter.shared
    let monitor = MonitorService()
    let workshop = WorkshopService()

    private let defaults = UserDefaults.standard

    var savedServerURL: String { defaults.string(forKey: "serverURL") ?? "" }

    init() {
        if let token = Keychain.read("token"), let url = defaults.string(forKey: "serverURL") {
            account = Account(
                serverURL: url,
                token: token,
                username: defaults.string(forKey: "username") ?? "",
                displayName: defaults.string(forKey: "displayName") ?? "",
                role: defaults.string(forKey: "role") ?? "customer"
            )
        }
        if let id = defaults.object(forKey: "vehicleId") as? Int, let key = Keychain.read("deviceKey") {
            vehicle = VehicleLink(id: id, name: defaults.string(forKey: "vehicleName") ?? "", key: key)
        }
        if let id = defaults.string(forKey: "adapterId") {
            adapter = AdapterChoice(id: id, name: defaults.string(forKey: "adapterName") ?? id)
        }
        if account != nil { Task { await reload() } }
    }

    func login(serverURL: String, username: String, password: String) async {
        busy = true
        loginError = nil
        defer { busy = false }
        let url = serverURL.trimmingCharacters(in: CharacterSet(charactersIn: "/ "))
        do {
            let result = try await UserApi.login(serverURL: url, username: username.trimmingCharacters(in: .whitespaces), password: password)
            defaults.set(url, forKey: "serverURL")
            defaults.set(result.username, forKey: "username")
            defaults.set(result.displayName, forKey: "displayName")
            defaults.set(result.role, forKey: "role")
            Keychain.write("token", result.token)
            account = Account(serverURL: url, token: result.token, username: result.username, displayName: result.displayName, role: result.role)
            await reload()
        } catch {
            loginError = error.localizedDescription
        }
    }

    func logout() async {
        if let api = userApi { await api.logout() }
        signOutLocally(message: nil)
    }

    func reload() async {
        guard let api = userApi else { return }
        busy = true
        defer { busy = false }
        do {
            vehicles = try await api.vehicles()
            if account?.isAdmin == true { customers = try await api.customers() }
        } catch {
            handle(error)
        }
    }

    func createVehicle(name: String) async {
        await perform { api in
            let link = try await api.createVehicle(name: name)
            self.store(link)
            self.vehicles = try await api.vehicles()
        }
    }

    func linkVehicle(_ info: VehicleInfo) async {
        await perform { api in self.store(try await api.linkVehicle(id: info.id)) }
    }

    func saveReport(label: String, customerId: Int?, snapshot: Snapshot, cleared: Bool) async -> String {
        guard let api = userApi else { return "Accesso richiesto" }
        do {
            try await api.saveWorkshopRead(label: label, customerId: customerId, snapshot: snapshot, cleared: cleared)
            return "Lettura salvata"
        } catch {
            handle(error)
            return error.localizedDescription
        }
    }

    func startMonitoring() {
        guard let account, let vehicle else { return vehicleMessage = "Scegli o crea un veicolo" }
        guard let adapter else { return vehicleMessage = "Scegli l'adattatore OBD" }
        vehicleMessage = nil
        ble.target = adapter
        monitor.start(serverURL: account.serverURL, deviceKey: vehicle.key, ble: ble)
    }

    func stopMonitoring() {
        monitor.stop()
    }

    private var userApi: UserApi? {
        account.map { UserApi(serverURL: $0.serverURL, token: $0.token) }
    }

    private func perform(_ work: @escaping (UserApi) async throws -> Void) async {
        guard let api = userApi else { return }
        busy = true
        vehicleMessage = nil
        defer { busy = false }
        do { try await work(api) } catch { handle(error) }
    }

    private func store(_ link: VehicleLink) {
        vehicle = link
        defaults.set(link.id, forKey: "vehicleId")
        defaults.set(link.name, forKey: "vehicleName")
        Keychain.write("deviceKey", link.key)
    }

    private func saveAdapter() {
        defaults.set(adapter?.id, forKey: "adapterId")
        defaults.set(adapter?.name, forKey: "adapterName")
    }

    private func handle(_ error: Error) {
        if let api = error as? ApiError, api.status == 401 {
            signOutLocally(message: "Sessione scaduta, accedi di nuovo")
        } else {
            vehicleMessage = error.localizedDescription
        }
    }

    private func signOutLocally(message: String?) {
        monitor.stop()
        workshop.reset()
        Keychain.write("token", nil)
        Keychain.write("deviceKey", nil)
        defaults.removeObject(forKey: "vehicleId")
        defaults.removeObject(forKey: "vehicleName")
        account = nil
        vehicle = nil
        vehicles = []
        customers = []
        loginError = message
    }
}

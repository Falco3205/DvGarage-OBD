import CoreBluetooth
import Foundation
import OBDCore

struct AdapterChoice: Identifiable, Equatable {
    let id: String
    let name: String
}

@MainActor
final class BleAdapter: NSObject, ObservableObject, ObdTransport {
    static let shared = BleAdapter()

    @Published private(set) var found: [AdapterChoice] = []
    @Published private(set) var scanning = false
    @Published private(set) var stateText = "Bluetooth non pronto"

    var target: AdapterChoice?

    private var central: CBCentralManager!
    private var peripherals: [UUID: CBPeripheral] = [:]
    private var peripheral: CBPeripheral?
    private var writeCharacteristic: CBCharacteristic?
    private var notifyCharacteristic: CBCharacteristic?
    private var assembler = PromptAssembler()

    private var poweredOnWaiters: [CheckedContinuation<Void, Error>] = []
    private var openContinuation: CheckedContinuation<Void, Error>?
    private var exchangeContinuation: CheckedContinuation<String, Error>?
    private var exchangeCommand = ""
    private var pendingCharacteristicServices = 0
    private var scanStopTask: Task<Void, Never>?

    override init() {
        super.init()
        central = CBCentralManager(delegate: self, queue: .main)
    }

    // MARK: Scanning

    func startScan(duration: TimeInterval = 10) {
        guard central.state == .poweredOn else { return }
        found = []
        scanning = true
        central.scanForPeripherals(withServices: nil, options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
        scanStopTask?.cancel()
        scanStopTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(duration * 1_000_000_000))
            guard !Task.isCancelled else { return }
            self?.stopScan()
        }
    }

    func stopScan() {
        scanStopTask?.cancel()
        if central.state == .poweredOn { central.stopScan() }
        scanning = false
    }

    // MARK: ObdTransport

    func open() async throws {
        guard let target, let uuid = UUID(uuidString: target.id) else {
            throw ObdError.transport("Nessun adattatore selezionato")
        }
        try await waitUntilPoweredOn()
        await close()

        let device = try await locate(uuid)
        peripheral = device
        device.delegate = self
        try await withThrowingTimeout(seconds: 20, onTimeout: ObdError.transport("Timeout connessione all'adattatore BLE")) { [self] in
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                openContinuation = continuation
                central.connect(device, options: nil)
            }
        } onTimeoutCleanup: { [self] in
            failOpen(ObdError.transport("Timeout connessione all'adattatore BLE"))
        }
    }

    func exchange(_ command: String, timeout: TimeInterval) async throws -> String {
        guard let peripheral, let characteristic = writeCharacteristic, exchangeContinuation == nil else {
            throw ObdError.transport("Adattatore non connesso")
        }
        assembler.reset()
        exchangeCommand = command

        let type: CBCharacteristicWriteType =
            characteristic.properties.contains(.write) ? .withResponse : .withoutResponse
        let payload = Data("\(command)\r".utf8)
        let chunk = max(20, min(peripheral.maximumWriteValueLength(for: type), 182))

        return try await withThrowingTimeout(seconds: timeout, onTimeout: ObdError.timeout(command)) { [self] in
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<String, Error>) in
                exchangeContinuation = continuation
                var offset = 0
                while offset < payload.count {
                    let end = min(offset + chunk, payload.count)
                    peripheral.writeValue(payload.subdata(in: offset..<end), for: characteristic, type: type)
                    offset = end
                }
            }
        } onTimeoutCleanup: { [self] in
            finishExchange(.failure(ObdError.timeout(command)))
        }
    }

    func close() async {
        finishExchange(.failure(ObdError.transport("Connessione chiusa")))
        failOpen(ObdError.transport("Connessione chiusa"))
        if let peripheral {
            if let notify = notifyCharacteristic { peripheral.setNotifyValue(false, for: notify) }
            central.cancelPeripheralConnection(peripheral)
        }
        peripheral = nil
        writeCharacteristic = nil
        notifyCharacteristic = nil
    }

    // MARK: Helpers

    private func waitUntilPoweredOn() async throws {
        switch central.state {
        case .poweredOn: return
        case .unauthorized: throw ObdError.transport("Permesso Bluetooth negato: abilitalo in Impostazioni")
        case .unsupported: throw ObdError.transport("Bluetooth LE non supportato su questo dispositivo")
        case .poweredOff: throw ObdError.transport("Attiva il Bluetooth")
        default: break
        }
        try await withThrowingTimeout(seconds: 8, onTimeout: ObdError.transport("Bluetooth non disponibile")) { [self] in
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                poweredOnWaiters.append(continuation)
            }
        } onTimeoutCleanup: { [self] in
            resumePoweredOnWaiters(.failure(ObdError.transport("Bluetooth non disponibile")))
        }
    }

    private func locate(_ uuid: UUID) async throws -> CBPeripheral {
        if let known = peripherals[uuid] ?? central.retrievePeripherals(withIdentifiers: [uuid]).first {
            return known
        }
        startScan(duration: 10)
        for _ in 0..<100 {
            if let discovered = peripherals[uuid] {
                stopScan()
                return discovered
            }
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        stopScan()
        throw ObdError.transport("Adattatore non trovato: accendi il quadro e avvicinati")
    }

    private func failOpen(_ error: Error) {
        guard let continuation = openContinuation else { return }
        openContinuation = nil
        continuation.resume(throwing: error)
    }

    private func succeedOpen() {
        guard let continuation = openContinuation else { return }
        openContinuation = nil
        continuation.resume()
    }

    private func finishExchange(_ result: Result<String, Error>) {
        guard let continuation = exchangeContinuation else { return }
        exchangeContinuation = nil
        continuation.resume(with: result)
    }

    private func resumePoweredOnWaiters(_ result: Result<Void, Error>) {
        let waiters = poweredOnWaiters
        poweredOnWaiters = []
        waiters.forEach { $0.resume(with: result) }
    }

    private func pickCharacteristics(_ services: [CBService]) -> (notify: CBCharacteristic, write: CBCharacteristic)? {
        let generic: Set<CBUUID> = [CBUUID(string: "1800"), CBUUID(string: "1801")]
        let all = services.filter { !generic.contains($0.uuid) }.flatMap { $0.characteristics ?? [] }
        let notify = all.filter { $0.properties.contains(.notify) || $0.properties.contains(.indicate) }
        let write = all.filter { $0.properties.contains(.write) || $0.properties.contains(.writeWithoutResponse) }
        if let shared = notify.first(where: { n in write.contains { $0.uuid == n.uuid && $0.service?.uuid == n.service?.uuid } }) {
            return (shared, shared)
        }
        guard let n = notify.first, let w = write.first else { return nil }
        return (n, w)
    }
}

// MARK: - CoreBluetooth delegates

extension BleAdapter: @preconcurrency CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn:
            stateText = "Bluetooth pronto"
            resumePoweredOnWaiters(.success(()))
        case .poweredOff:
            stateText = "Bluetooth spento"
        case .unauthorized:
            stateText = "Permesso Bluetooth negato"
        case .unsupported:
            stateText = "Bluetooth LE non supportato"
        default:
            stateText = "Bluetooth non pronto"
        }
        if central.state != .poweredOn && central.state != .unknown && central.state != .resetting {
            resumePoweredOnWaiters(.failure(ObdError.transport(stateText)))
        }
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        peripherals[peripheral.identifier] = peripheral
        let name = peripheral.name ?? advertisementData[CBAdvertisementDataLocalNameKey] as? String
        guard let name, !name.isEmpty else { return }
        let choice = AdapterChoice(id: peripheral.identifier.uuidString, name: name)
        if !found.contains(choice) {
            found.append(choice)
            found.sort { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        }
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.discoverServices(nil)
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        failOpen(ObdError.transport(error?.localizedDescription ?? "Connessione BLE fallita"))
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        let reason = ObdError.transport(error?.localizedDescription ?? "Adattatore disconnesso")
        failOpen(reason)
        finishExchange(.failure(reason))
        if peripheral === self.peripheral {
            self.peripheral = nil
            writeCharacteristic = nil
            notifyCharacteristic = nil
        }
    }
}

extension BleAdapter: @preconcurrency CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        if let error { return failOpen(ObdError.transport(error.localizedDescription)) }
        let services = peripheral.services ?? []
        pendingCharacteristicServices = services.count
        if services.isEmpty { return failOpen(ObdError.transport("Nessun servizio sull'adattatore")) }
        services.forEach { peripheral.discoverCharacteristics(nil, for: $0) }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        pendingCharacteristicServices -= 1
        guard pendingCharacteristicServices <= 0 else { return }
        guard let picked = pickCharacteristics(peripheral.services ?? []) else {
            return failOpen(ObdError.transport("L'adattatore non espone una porta seriale BLE (probabile adattatore Bluetooth Classic, non compatibile con iPhone)"))
        }
        writeCharacteristic = picked.write
        notifyCharacteristic = picked.notify
        peripheral.setNotifyValue(true, for: picked.notify)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        if let error { return failOpen(ObdError.transport(error.localizedDescription)) }
        if characteristic.isNotifying { succeedOpen() }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard error == nil, let data = characteristic.value, characteristic === notifyCharacteristic else { return }
        if let reply = assembler.append(data) { finishExchange(.success(reply)) }
    }
}

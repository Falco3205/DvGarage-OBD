import Foundation
import OBDCore
import UIKit

enum MonitorPhase {
    case stopped, connecting, online, error
}

private struct CommandRefused: Error {
    let message: String
}

@MainActor
final class MonitorService: ObservableObject {
    @Published private(set) var phase = MonitorPhase.stopped
    @Published private(set) var message = ""
    @Published private(set) var snapshot: Snapshot?
    @Published private(set) var lastSync: Date?

    private var task: Task<Void, Never>?

    var isRunning: Bool { task != nil }

    func start(serverURL: String, deviceKey: String, ble: BleAdapter) {
        guard task == nil else { return }
        phase = .connecting
        message = "Connessione in corso"
        UIApplication.shared.isIdleTimerDisabled = true
        let api = DeviceApi(serverURL: serverURL, key: deviceKey)
        task = Task { [weak self] in
            await self?.loop(api: api, ble: ble)
        }
    }

    func stop() {
        task?.cancel()
        task = nil
        UIApplication.shared.isIdleTimerDisabled = false
        phase = .stopped
        message = "Fermato"
    }

    private func loop(api: DeviceApi, ble: BleAdapter) async {
        var backoff: UInt64 = 3
        while !Task.isCancelled {
            do {
                try await runSession(api: api, ble: ble)
                backoff = 3
            } catch is CancellationError {
                break
            } catch {
                if Task.isCancelled { break }
                phase = .error
                message = error.localizedDescription
                try? await Task.sleep(nanoseconds: backoff * 1_000_000_000)
                backoff = min(backoff * 2, 60)
            }
        }
        await ble.close()
    }

    private func runSession(api: DeviceApi, ble: BleAdapter) async throws {
        phase = .connecting
        message = "Connessione all'adattatore"
        let transport = TracingTransport(ble, sink: TraceLog.sink)
        try await transport.open()
        do {
            let session = ObdSession(client: Elm327Client(transport: transport))
            try await session.connect()
            _ = try await sync(session, api: api)
            try await serve(session, api: api)
        } catch {
            await transport.close()
            throw error
        }
        await transport.close()
    }

    private func serve(_ session: ObdSession, api: DeviceApi) async throws {
        var lastFullSync = Date()
        while !Task.isCancelled {
            let command: ServerCommand?
            do {
                command = try await api.nextCommand(waitSeconds: 20)
            } catch let error as ApiError where error.status == 401 {
                throw ObdError.transport("Veicolo non più collegato: accedi di nuovo e ricollega il veicolo")
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                if Task.isCancelled { throw CancellationError() }
                message = "Server non raggiungibile: \(error.localizedDescription)"
                try await Task.sleep(nanoseconds: 5_000_000_000)
                continue
            }
            if let command {
                switch command.type {
                case "clear_dtc": try await execute(command, api: api) { try await self.clear(session, api: api) }
                case "rescan":
                    try await execute(command, api: api) {
                        _ = try await self.sync(session, api: api)
                        return "Rilettura completata"
                    }
                default: try await api.reportResult(commandId: command.id, ok: false, message: "Comando non supportato")
                }
            }
            if Date().timeIntervalSince(lastFullSync) >= 300 {
                _ = try await sync(session, api: api)
                lastFullSync = Date()
            }
        }
    }

    private func clear(_ session: ObdSession, api: DeviceApi) async throws -> String {
        guard try await session.clearCodes() else {
            throw CommandRefused(message: "La centralina ha rifiutato la cancellazione (quadro acceso, motore spento)")
        }
        let remaining = try await sync(session, api: api)
        return remaining == 0 ? "Errori cancellati" : "Cancellati, ma \(remaining) errore/i risultano ancora presenti"
    }

    private func execute(_ command: ServerCommand, api: DeviceApi, action: () async throws -> String) async throws {
        do {
            try await api.reportResult(commandId: command.id, ok: true, message: try await action())
        } catch let refused as CommandRefused {
            try await api.reportResult(commandId: command.id, ok: false, message: refused.message)
        } catch let error as ObdError {
            try? await api.reportResult(commandId: command.id, ok: false, message: error.localizedDescription)
            throw error
        }
    }

    private func sync(_ session: ObdSession, api: DeviceApi) async throws -> Int {
        let snapshot = try await session.readSnapshot()
        try await api.uploadScan(snapshot)
        self.snapshot = snapshot
        lastSync = Date()
        phase = .online
        message = "Online"
        return snapshot.dtcs.count
    }
}

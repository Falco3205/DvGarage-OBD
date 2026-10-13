import Foundation
import OBDCore

@MainActor
final class WorkshopService: ObservableObject {
    @Published private(set) var busy = false
    @Published private(set) var message = ""
    @Published private(set) var isError = false
    @Published private(set) var snapshot: Snapshot?
    @Published private(set) var cleared = false

    func read(ble: BleAdapter, adapter: AdapterChoice) {
        run(ble: ble, adapter: adapter, progress: "Lettura in corso…") { session in
            let snapshot = try await session.readSnapshot()
            let text = snapshot.dtcs.isEmpty ? "Nessun errore presente" : "\(snapshot.dtcs.count) errore/i letti"
            return (snapshot, text, false)
        }
    }

    func clear(ble: BleAdapter, adapter: AdapterChoice) {
        run(ble: ble, adapter: adapter, progress: "Cancellazione in corso…") { session in
            guard try await session.clearCodes() else {
                throw ObdError.protocolFailure("La centralina ha rifiutato la cancellazione (quadro acceso, motore spento)")
            }
            let snapshot = try await session.readSnapshot()
            let text = snapshot.dtcs.isEmpty
                ? "Errori cancellati"
                : "Cancellati, ma \(snapshot.dtcs.count) errore/i risultano ancora presenti"
            return (snapshot, text, true)
        }
    }

    func reset() {
        guard !busy else { return }
        snapshot = nil
        message = ""
        isError = false
        cleared = false
    }

    private func run(
        ble: BleAdapter,
        adapter: AdapterChoice,
        progress: String,
        action: @escaping (ObdSession) async throws -> (Snapshot, String, Bool)
    ) {
        guard !busy else { return }
        busy = true
        isError = false
        message = progress
        ble.target = adapter
        Task {
            let transport = TracingTransport(ble, sink: TraceLog.sink)
            do {
                try await transport.open()
                let session = ObdSession(client: Elm327Client(transport: transport))
                try await session.connect()
                let (snapshot, text, didClear) = try await action(session)
                self.snapshot = snapshot
                self.message = text
                self.cleared = self.cleared || didClear
            } catch {
                self.message = error.localizedDescription
                self.isError = true
            }
            await transport.close()
            self.busy = false
        }
    }
}

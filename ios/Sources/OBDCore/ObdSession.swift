import Foundation

public final class ObdSession {
    private let client: Elm327Client

    public init(client: Elm327Client) {
        self.client = client
    }

    public func connect() async throws {
        try await client.initialize()
    }

    public func readSnapshot() async throws -> Snapshot {
        let statusMessages = try await messagesOrThrow(
            try await client.request("0101"),
            noData: "Il veicolo non risponde. Accendi il quadro."
        )
        guard let status = ObdParser.decodeMonitorStatus(statusMessages) else {
            throw ObdError.protocolFailure("Il veicolo non ha restituito lo stato dei monitor")
        }

        let stored = ObdParser.decodeDtcs(try await requiredMessages("03"), responseId: 0x43, kind: .stored)
        let pending = ObdParser.decodeDtcs(try await optionalMessages("07"), responseId: 0x47, kind: .pending)
        let permanent = ObdParser.decodeDtcs(try await optionalMessages("0A"), responseId: 0x4A, kind: .permanent)
        let vin = ObdParser.decodeVin(try await optionalMessages("0902"))

        return Snapshot(vin: vin, milOn: status.milOn, dtcs: stored + pending + permanent)
    }

    public func clearCodes() async throws -> Bool {
        if case .messages(let messages) = try await client.request("04") {
            return ObdParser.isPositiveResponse(messages, responseId: 0x44)
        }
        return false
    }

    private func requiredMessages(_ command: String) async throws -> [[UInt8]] {
        switch try await client.request(command) {
        case .messages(let messages): return messages
        case .noData: return []
        case .failure(let message): throw ObdError.protocolFailure(message)
        }
    }

    private func optionalMessages(_ command: String) async throws -> [[UInt8]] {
        if case .messages(let messages) = try await client.request(command) { return messages }
        return []
    }

    private func messagesOrThrow(_ reply: Reply, noData: String) async throws -> [[UInt8]] {
        switch reply {
        case .messages(let messages): return messages
        case .noData: throw ObdError.protocolFailure(noData)
        case .failure(let message): throw ObdError.protocolFailure(message)
        }
    }
}

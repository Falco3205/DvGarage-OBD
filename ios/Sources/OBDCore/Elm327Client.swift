import Foundation

public final class Elm327Client {
    private let transport: ObdTransport
    private static let setupCommands = ["ATE0", "ATL0", "ATS0", "ATH0", "ATSP0"]

    public init(transport: ObdTransport) {
        self.transport = transport
    }

    public func initialize() async throws {
        _ = try await transport.exchange("ATZ", timeout: 6)
        for command in Self.setupCommands {
            let reply = try await transport.exchange(command, timeout: 3)
            guard reply.uppercased().contains("OK") else {
                throw ObdError.protocolFailure("L'adattatore ha rifiutato \(command): \(reply.trimmingCharacters(in: .whitespacesAndNewlines))")
            }
        }
    }

    public func request(_ command: String, timeout: TimeInterval = 15) async throws -> Reply {
        ObdParser.parse(try await transport.exchange(command, timeout: timeout))
    }
}

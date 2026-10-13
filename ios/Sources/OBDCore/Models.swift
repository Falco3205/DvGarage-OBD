import Foundation

public enum DtcKind: String, Sendable {
    case stored
    case pending
    case permanent
}

public struct Dtc: Equatable, Sendable {
    public let code: String
    public let kind: DtcKind

    public init(code: String, kind: DtcKind) {
        self.code = code
        self.kind = kind
    }
}

public struct Snapshot: Equatable, Sendable {
    public let vin: String?
    public let milOn: Bool
    public let dtcs: [Dtc]

    public init(vin: String?, milOn: Bool, dtcs: [Dtc]) {
        self.vin = vin
        self.milOn = milOn
        self.dtcs = dtcs
    }
}

public struct MonitorStatus: Equatable, Sendable {
    public let milOn: Bool
    public let dtcCount: Int
}

public enum ObdError: Error, LocalizedError, Equatable {
    case timeout(String)
    case protocolFailure(String)
    case transport(String)

    public var errorDescription: String? {
        switch self {
        case .timeout(let command): return "Nessuna risposta dall'adattatore a '\(command)'"
        case .protocolFailure(let message): return message
        case .transport(let message): return message
        }
    }
}

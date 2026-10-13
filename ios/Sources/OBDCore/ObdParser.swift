import Foundation

public enum Reply: Equatable {
    case messages([[UInt8]])
    case noData
    case failure(String)
}

public enum ObdParser {
    private static let errorMarkers = ["UNABLETOCONNECT", "ERROR", "STOPPED", "BUFFERFULL", "LVRESET"]

    public static func parse(_ raw: String) -> Reply {
        let lines = raw.uppercased()
            .split(whereSeparator: { $0 == "\r" || $0 == "\n" })
            .map { String($0.filter { !$0.isWhitespace }) }
            .filter { !$0.isEmpty && $0 != "SEARCHING..." && !isBusInitOk($0) }

        var sawNoData = false
        var messages: [[UInt8]] = []
        var group = ""
        var groupLength: Int?
        var pendingLength: Int?

        func closeGroup() {
            guard !group.isEmpty else { return }
            var bytes = hexToBytes(group)
            if let limit = groupLength, limit < bytes.count { bytes = Array(bytes.prefix(limit)) }
            messages.append(bytes)
            group = ""
            groupLength = nil
        }

        for (index, line) in lines.enumerated() {
            if line == "NODATA" {
                sawNoData = true
            } else if line == "?" || errorMarkers.contains(where: { line.contains($0) }) {
                return .failure(line)
            } else if let payload = indexedPayload(line) {
                if line.hasPrefix("0:") {
                    closeGroup()
                    groupLength = pendingLength
                }
                pendingLength = nil
                if payload.count % 2 != 0 { return .failure("Risposta inattesa: \(line)") }
                group += payload
            } else if line.count == 3, line.allSatisfy(\.isHexDigit),
                      index + 1 < lines.count, lines[index + 1].hasPrefix("0:") {
                pendingLength = Int(line, radix: 16)
            } else if line.count % 2 == 0, line.allSatisfy(\.isHexDigit) {
                closeGroup()
                messages.append(hexToBytes(line))
            } else {
                return .failure("Risposta inattesa: \(line)")
            }
        }
        closeGroup()

        if !messages.isEmpty { return .messages(messages) }
        return sawNoData ? .noData : .failure("Risposta vuota")
    }

    public static func decodeDtcs(_ messages: [[UInt8]], responseId: UInt8, kind: DtcKind) -> [Dtc] {
        var seen = Set<String>()
        var codes: [String] = []
        for message in messages where message.first == responseId {
            let body = Array(message.dropFirst())
            var i = body.count % 2 == 1 ? 1 : 0
            while i + 1 < body.count {
                let hi = body[i]
                let lo = body[i + 1]
                if hi != 0 || lo != 0 {
                    let code = formatDtc(hi, lo)
                    if seen.insert(code).inserted { codes.append(code) }
                }
                i += 2
            }
        }
        return codes.map { Dtc(code: $0, kind: kind) }
    }

    public static func decodeMonitorStatus(_ messages: [[UInt8]]) -> MonitorStatus? {
        let statuses = messages.filter { $0.count >= 3 && $0[0] == 0x41 && $0[1] == 0x01 }
        guard !statuses.isEmpty else { return nil }
        return MonitorStatus(
            milOn: statuses.contains { $0[2] & 0x80 != 0 },
            dtcCount: statuses.map { Int($0[2] & 0x7F) }.max() ?? 0
        )
    }

    public static func decodeVin(_ messages: [[UInt8]]) -> String? {
        let chars = messages
            .filter { $0.count > 3 && $0[0] == 0x49 && $0[1] == 0x02 }
            .sorted { $0[2] < $1[2] }
            .flatMap { $0.dropFirst(3) }
            .filter { (0x30...0x5A).contains($0) }
            .map { Character(UnicodeScalar($0)) }
        let vin = String(chars)
        return isValidVin(vin) ? vin : nil
    }

    public static func isPositiveResponse(_ messages: [[UInt8]], responseId: UInt8) -> Bool {
        messages.contains { $0.first == responseId }
    }

    private static func isValidVin(_ vin: String) -> Bool {
        vin.count == 17 && vin.allSatisfy { ($0.isNumber || $0.isUppercase) && !"IOQ".contains($0) && $0.isASCII }
    }

    private static func formatDtc(_ hi: UInt8, _ lo: UInt8) -> String {
        let system = Array("PCBU")[Int(hi >> 6)]
        let digit = (hi >> 4) & 0x3
        let third = String(hi & 0xF, radix: 16, uppercase: true)
        let tail = String(lo, radix: 16, uppercase: true)
        return "\(system)\(digit)\(third)\(tail.count == 1 ? "0" + tail : tail)"
    }

    private static func isBusInitOk(_ line: String) -> Bool {
        line.hasPrefix("BUSINIT") && line.hasSuffix("OK")
    }

    private static func indexedPayload(_ line: String) -> String? {
        let chars = Array(line)
        guard chars.count >= 2, chars[0].isHexDigit, chars[1] == ":" else { return nil }
        let payload = String(chars.dropFirst(2))
        return payload.allSatisfy(\.isHexDigit) ? payload : nil
    }

    private static func hexToBytes(_ hex: String) -> [UInt8] {
        let chars = Array(hex)
        return stride(from: 0, to: chars.count - 1, by: 2).compactMap {
            UInt8(String(chars[$0...$0 + 1]), radix: 16)
        }
    }
}

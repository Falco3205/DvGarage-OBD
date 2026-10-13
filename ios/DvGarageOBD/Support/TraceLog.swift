import Foundation

@MainActor
final class TraceLog: ObservableObject {
    static let shared = TraceLog()

    @Published private(set) var lines: [String] = []

    private let maxLines = 400
    private let formatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss.SSS"
        return f
    }()

    func add(_ message: String) {
        lines.append("\(formatter.string(from: Date())) \(message)")
        if lines.count > maxLines { lines.removeFirst(lines.count - maxLines) }
    }

    func clear() {
        lines = []
    }

    var text: String {
        lines.joined(separator: "\n")
    }

    nonisolated static func sink(_ message: String) {
        Task { @MainActor in TraceLog.shared.add(message) }
    }
}

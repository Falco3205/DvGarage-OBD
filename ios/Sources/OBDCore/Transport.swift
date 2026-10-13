import Foundation

public protocol ObdTransport: AnyObject {
    func open() async throws
    func exchange(_ command: String, timeout: TimeInterval) async throws -> String
    func close() async
}

public struct PromptAssembler {
    private var buffer = ""

    public init() {}

    public mutating func reset() {
        buffer = ""
    }

    public mutating func append(_ data: Data) -> String? {
        buffer += String(decoding: data, as: UTF8.self)
        guard let prompt = buffer.firstIndex(of: ">") else { return nil }
        let reply = String(buffer[buffer.startIndex..<prompt])
        buffer = String(buffer[buffer.index(after: prompt)...])
        return reply
    }
}

public final class TracingTransport: ObdTransport {
    private let wrapped: ObdTransport
    private let sink: @Sendable (String) -> Void

    public init(_ wrapped: ObdTransport, sink: @escaping @Sendable (String) -> Void) {
        self.wrapped = wrapped
        self.sink = sink
    }

    public func open() async throws {
        sink("open")
        do {
            try await wrapped.open()
            sink("opened")
        } catch {
            sink("open failed: \(error.localizedDescription)")
            throw error
        }
    }

    public func exchange(_ command: String, timeout: TimeInterval) async throws -> String {
        sink("> \(command)")
        do {
            let reply = try await wrapped.exchange(command, timeout: timeout)
            let flat = reply.trimmingCharacters(in: .whitespacesAndNewlines)
                .replacingOccurrences(of: "\r", with: "|")
                .replacingOccurrences(of: "\n", with: "|")
            sink("< \(flat)")
            return reply
        } catch {
            sink("! \(error.localizedDescription)")
            throw error
        }
    }

    public func close() async {
        sink("close")
        await wrapped.close()
    }
}

import XCTest
@testable import OBDCore

private final class ScriptedTransport: ObdTransport {
    var replies: [String: String]
    private(set) var sent: [String] = []

    init(_ replies: [String: String]) {
        self.replies = replies
    }

    func open() async throws {}
    func close() async {}

    func exchange(_ command: String, timeout: TimeInterval) async throws -> String {
        sent.append(command)
        return (replies[command] ?? "?") + "\r\r"
    }
}

final class ObdSessionTests: XCTestCase {
    private let healthy: [String: String] = [
        "ATZ": "ELM327 v1.5", "ATE0": "OK", "ATL0": "OK", "ATS0": "OK", "ATH0": "OK", "ATSP0": "OK",
        "0101": "SEARCHING...\r41 01 82 07 E5 00",
        "03": "43 02 01 33 04 20 00 00",
        "07": "47 01 01 71 00 00 00 00",
        "0A": "NO DATA",
        "0902": "014\r0: 49 02 01 5A 46 41\r1: 31 36 39 30 30 30 30\r2: 31 32 33 34 35 36 37",
        "04": "44",
    ]

    private func session(_ overrides: [String: String] = [:]) -> (ObdSession, ScriptedTransport) {
        let transport = ScriptedTransport(healthy.merging(overrides) { $1 })
        return (ObdSession(client: Elm327Client(transport: transport)), transport)
    }

    func testReadsFullSnapshot() async throws {
        let (session, transport) = session()
        try await session.connect()
        let snapshot = try await session.readSnapshot()

        XCTAssertEqual(snapshot.vin, "ZFA16900001234567")
        XCTAssertTrue(snapshot.milOn)
        XCTAssertEqual(snapshot.dtcs, [
            Dtc(code: "P0133", kind: .stored),
            Dtc(code: "P0420", kind: .stored),
            Dtc(code: "P0171", kind: .pending),
        ])
        XCTAssertEqual(Array(transport.sent.prefix(6)), ["ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0"])
    }

    func testClearsOnlyOnPositiveResponse() async throws {
        let ok = try await session().0.clearCodes()
        let refused = try await session(["04": "7F 04 22"]).0.clearCodes()
        let silent = try await session(["04": "NO DATA"]).0.clearCodes()
        XCTAssertTrue(ok)
        XCTAssertFalse(refused)
        XCTAssertFalse(silent)
    }

    func testFailsClearlyWhenVehicleDoesNotAnswer() async {
        for reply in ["UNABLE TO CONNECT", "NO DATA"] {
            do {
                _ = try await session(["0101": reply]).0.readSnapshot()
                XCTFail("expected failure for \(reply)")
            } catch {
                XCTAssertTrue(error is ObdError)
            }
        }
    }

    func testInitialisationFailsWhenAdapterRejectsSetup() async {
        do {
            try await session(["ATH0": "?"]).0.connect()
            XCTFail("expected failure")
        } catch {
            XCTAssertTrue(error is ObdError)
        }
    }

    func testTracingRecordsCommandsAndReplies() async throws {
        final class Log: @unchecked Sendable { var lines: [String] = [] }
        let log = Log()
        let traced = TracingTransport(ScriptedTransport(["0100": "41 00 BE 3E"])) { log.lines.append($0) }
        _ = try await traced.exchange("0100", timeout: 1)
        XCTAssertEqual(log.lines, ["> 0100", "< 41 00 BE 3E"])
    }
}

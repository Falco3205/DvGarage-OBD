import XCTest
@testable import OBDCore

final class ObdParserTests: XCTestCase {
    private func messages(_ raw: String, file: StaticString = #filePath, line: UInt = #line) -> [[UInt8]] {
        guard case .messages(let parsed) = ObdParser.parse(raw) else {
            XCTFail("expected messages for \(raw)", file: file, line: line)
            return []
        }
        return parsed
    }

    private func codes(_ raw: String, _ id: UInt8, _ kind: DtcKind = .stored) -> [String] {
        ObdParser.decodeDtcs(messages(raw), responseId: id, kind: kind).map(\.code)
    }

    func testDecodesCanSingleFrameWithCountByte() {
        XCTAssertEqual(codes("43 02 01 33 04 20 00 00\r\r", 0x43), ["P0133", "P0420"])
    }

    func testDecodesLegacyLinesWithoutCountByte() {
        XCTAssertEqual(codes("43 01 33 00 00 00 00\r\r", 0x43), ["P0133"])
    }

    func testDecodesAllFourSystems() {
        XCTAssertEqual(codes("43 04 01 00 41 23 92 34 E3 45", 0x43), ["P0100", "C0123", "B1234", "U2345"])
    }

    func testEmptyListMeansNoCodes() {
        XCTAssertEqual(codes("43 00\r", 0x43), [])
        XCTAssertEqual(codes("43 00 00 00 00 00 00\r", 0x43), [])
    }

    func testAssemblesCanMultiFrameAndHonoursDeclaredLength() {
        let raw = "00E\r0: 43 06 01 33 04 20\r1: 01 71 03 00 00 00\r\r"
        XCTAssertEqual(codes(raw, 0x43), ["P0133", "P0420", "P0171", "P0300"])
    }

    func testKeepsMultiFrameRepliesOfDifferentEcusApart() {
        let raw = "00A\r0: 43 04 01 33 04 20\r1: 00 00 00 00 00 00\r008\r0: 43 03 01 71 03 00\r1: 00 00 00 00 00 00\r"
        XCTAssertEqual(messages(raw).count, 2)
        XCTAssertEqual(codes(raw, 0x43), ["P0133", "P0420", "P0171", "P0300"])
    }

    func testMergesSameCodeFromTwoEcus() {
        XCTAssertEqual(codes("43 01 03 00\r43 01 03 00\r", 0x43), ["P0300"])
    }

    func testIgnoresSearchingAndBusInit() {
        XCTAssertEqual(codes("SEARCHING...\rBUS INIT: OK\r43 01 01 33\r", 0x43), ["P0133"])
    }

    func testNegativeResponseYieldsNoCodes() {
        XCTAssertEqual(codes("7F 0A 11\r", 0x4A, .permanent), [])
    }

    func testClassifiesNoDataAndErrors() {
        XCTAssertEqual(ObdParser.parse("NO DATA\r"), .noData)
        for raw in ["UNABLE TO CONNECT\r", "CAN ERROR\r", "BUS INIT: ...ERROR\r", "?\r", "garbage!\r", ""] {
            guard case .failure = ObdParser.parse(raw) else {
                return XCTFail("expected failure for \(raw)")
            }
        }
    }

    func testDecodesMonitorStatus() {
        XCTAssertEqual(ObdParser.decodeMonitorStatus(messages("41 01 83 07 E5 00\r")), MonitorStatus(milOn: true, dtcCount: 3))
        XCTAssertEqual(ObdParser.decodeMonitorStatus(messages("41 01 00 07 E5 00\r"))?.milOn, false)
        XCTAssertNil(ObdParser.decodeMonitorStatus(messages("7F 01 12\r")))
    }

    func testDecodesVinFromCanMultiFrame() {
        let raw = "014\r0: 49 02 01 5A 46 41\r1: 31 36 39 30 30 30 30\r2: 31 32 33 34 35 36 37\r"
        XCTAssertEqual(ObdParser.decodeVin(messages(raw)), "ZFA16900001234567")
    }

    func testDecodesVinFromLegacyLines() {
        let raw = "49 02 01 00 00 00 5A\r49 02 02 46 41 31 36\r49 02 03 39 30 30 30\r49 02 04 30 31 32 33\r49 02 05 34 35 36 37\r"
        XCTAssertEqual(ObdParser.decodeVin(messages(raw)), "ZFA16900001234567")
    }

    func testRejectsMalformedVin() {
        XCTAssertNil(ObdParser.decodeVin(messages("49 02 01 41 42 43\r")))
    }

    func testRecognisesClearResponse() {
        XCTAssertTrue(ObdParser.isPositiveResponse(messages("44\r"), responseId: 0x44))
        XCTAssertFalse(ObdParser.isPositiveResponse(messages("7F 04 22\r"), responseId: 0x44))
    }

    func testPromptAssemblerSplitsReplies() {
        var assembler = PromptAssembler()
        XCTAssertNil(assembler.append(Data("41 00 ".utf8)))
        XCTAssertEqual(assembler.append(Data("BE 3E\r\r>".utf8)), "41 00 BE 3E\r\r")
        XCTAssertNil(assembler.append(Data("OK".utf8)))
    }
}

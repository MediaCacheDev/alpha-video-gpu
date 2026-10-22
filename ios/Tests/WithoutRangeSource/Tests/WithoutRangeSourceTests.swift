import AlphaVideoGPU
import XCTest

final class WithoutRangeSourceTests: XCTestCase {
    func testTheAddOnIsLeftOut() {
        XCTAssertNil(NSClassFromString("RangeSource"))
    }

    func testDataSourceReportsNoSourceModule() throws {
        let outcome = try openInView("fixtures://card/astc4x4_stream.ktx2", dataSource: "fixtures")
        XCTAssertEqual(outcome.code, AlphaVideoGpuErrorNoSourceModule)
        XCTAssertTrue(outcome.error?.contains("needs the range-source add-on") == true, outcome.error ?? "")
    }

    func testPlaysAFile() throws {
        let outcome = try openInView(fixture("astc4x4_stream.ktx2"))
        XCTAssertEqual(outcome.loaded, true, outcome.error ?? "")
    }
}

struct Outcome {
    var loaded = false
    var error: String?
    var code: String?
}

struct LoadTimeout: Error {}

/** A 256x256 test card in tests/fixtures; the Simulator reads the Mac's files. */
func fixture(_ name: String) -> String {
    URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        .deletingLastPathComponent().deletingLastPathComponent()
        .appendingPathComponent("tests/fixtures/card_256x256/\(name)").path
}

/** Shows `source` in an AlphaVideoGpuView, through `dataSource` if given, and waits for its load or its error. */
func openInView(_ source: String, dataSource: String? = nil, timeout: TimeInterval = 10) throws -> Outcome {
    var outcome = Outcome()
    var done = false
    let view = AlphaVideoGpuView(frame: CGRect(x: 0, y: 0, width: 256, height: 256))
    view.onLoad = { _, _, _, _ in
        outcome.loaded = true
        done = true
    }
    view.onError = { error, code in
        outcome.error = error
        outcome.code = code
        done = true
    }
    view.dataSource = dataSource
    view.source = source
    let deadline = Date(timeIntervalSinceNow: timeout)
    while !done && Date() < deadline {
        RunLoop.main.run(mode: .default, before: Date(timeIntervalSinceNow: 0.05))
    }
    view.cleanup()
    guard done else { throw LoadTimeout() }
    return outcome
}

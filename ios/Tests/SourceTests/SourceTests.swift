import AlphaVideoGPU
import RangeSource
import XCTest

/** Serves the test card's files from disk, whatever the URL's path names. */
final class FixtureSource: NSObject, RangeDataSource {
    private let lock = NSLock()
    private var count = 0

    var reads: Int {
        lock.lock()
        defer { lock.unlock() }
        return count
    }

    func read(
        url: URL, offset: Int64, length: Int64, headers: [String: String],
        response: @escaping (RangeDataInfo) -> Void, data: @escaping (Data) -> Void,
        completion: @escaping (Error?) -> Void
    ) -> RangeCancellable {
        lock.lock()
        count += 1
        lock.unlock()
        DispatchQueue.global().async {
            guard let bytes = FileManager.default.contents(atPath: fixture(url.lastPathComponent)) else {
                completion(NSError(domain: "test", code: 1, userInfo: [NSLocalizedDescriptionKey: "no \(url)"]))
                return
            }
            response(RangeDataInfo(size: Int64(bytes.count), etag: nil, lastModified: nil))
            let start = Int(min(offset, Int64(bytes.count)))
            let end = Int(min(offset + length, Int64(bytes.count)))
            data(bytes.subdata(in: start..<end))
            completion(nil)
        }
        return Cancel()
    }

    final class Cancel: NSObject, RangeCancellable {
        func cancel() {}
    }
}

// Links the RangeSource product, which the player never names: it has to find the add-on by
// itself, and read a data source registered with it.
final class SourceTests: XCTestCase {
    func testPlaysThroughADataSource() throws {
        let source = FixtureSource()
        RangeDataSources.register(source, name: "fixtures")
        defer { RangeDataSources.unregister(name: "fixtures") }
        let outcome = try openInView("fixtures://card/astc4x4_stream.ktx2", dataSource: "fixtures")
        XCTAssertEqual(outcome.loaded, true, outcome.error ?? "")
        XCTAssertGreaterThan(source.reads, 0)
    }

    func testUnregisteredDataSourceFails() throws {
        let outcome = try openInView("fixtures://card/astc4x4_stream.ktx2", dataSource: "nobody")
        XCTAssertNil(outcome.code)
        XCTAssertTrue(outcome.error?.contains("No data source is registered as \"nobody\"") == true, outcome.error ?? "")
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
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
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

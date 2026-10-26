// swift-tools-version:6.1
import Foundation
import PackageDescription

// The player as an app gets it with the range-source add-on left out (the AlphaVideoGPUCore
// product): it has to build and link without it, and report NO_SOURCE_MODULE for a data source.
//
//   cd ios/Tests/WithoutRangeSource && xcodebuild test -scheme WithoutRangeSource-Package \
//     -destination 'platform=iOS Simulator,name=iPhone 16 Pro'
//
// A path dependency is named after its folder, whatever the checkout is called.
let playerDir = URL(fileURLWithPath: #filePath)
    .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()

let package = Package(
    name: "WithoutRangeSource",
    platforms: [.iOS(.v13)],
    dependencies: [
        .package(path: playerDir.path),
    ],
    targets: [
        .testTarget(
            name: "WithoutRangeSourceTests",
            dependencies: [.product(name: "AlphaVideoGPUCore", package: playerDir.lastPathComponent)],
            path: "Tests"
        ),
    ],
    swiftLanguageModes: [.v5]
)

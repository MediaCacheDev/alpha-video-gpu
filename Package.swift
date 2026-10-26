// swift-tools-version:6.1
import PackageDescription

// URLs and data sources are read through range-source, which the AlphaVideoGPU product brings.
// The player finds it at runtime, so an app with AlphaVideoGPUCore instead gets the basic input:
// URLs downloaded whole. These are products rather than a package trait because Xcode before 26.4
// applies no traits, default ones included.
// RANGE_SOURCE_PATH, a local range-source checkout, replaces its release.
let rangeSource: Package.Dependency = Context.environment["RANGE_SOURCE_PATH"].map { .package(path: $0) }
    ?? .package(url: "https://github.com/MediaCacheDev/range-source.git", from: "1.0.0")

let package = Package(
    name: "AlphaVideoGPU",
    platforms: [.iOS(.v13)],
    products: [
        .library(name: "AlphaVideoGPU", targets: ["AlphaVideoGPU", "AlphaVideoGPUSource"]),
        .library(name: "AlphaVideoGPUCore", targets: ["AlphaVideoGPU"]),
    ],
    dependencies: [rangeSource],
    targets: [
        .target(
            name: "AlphaVideoGPU",
            path: "ios/Sources/AlphaVideoGPU",
            // AlphaVideoGpuView.mm compiles it, and zstd, by #include
            exclude: ["astc_decomp.cpp"],
            publicHeadersPath: "include",
            cxxSettings: [
                .headerSearchPath("../../../third_party"),
                // range-source's headers, vendored by scripts/vendor_range_source.sh: its C interface and the basic input
                .headerSearchPath("../../../third_party/range-source/include"),
            ],
            linkerSettings: [
                .linkedFramework("Metal"),
                .linkedFramework("MetalKit"),
            ]
        ),
        .target(
            name: "AlphaVideoGPUSource",
            dependencies: [.product(name: "RangeSource", package: "range-source")],
            path: "ios/Sources/AlphaVideoGPUSource"
        ),
        // With the add-on, found at runtime; ios/Tests/WithoutRangeSource builds without it
        .testTarget(
            name: "SourceTests",
            dependencies: ["AlphaVideoGPU", .product(name: "RangeSource", package: "range-source")],
            path: "ios/Tests/SourceTests"
        ),
    ],
    swiftLanguageModes: [.v5],
    cxxLanguageStandard: .cxx17
)

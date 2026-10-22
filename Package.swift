// swift-tools-version:6.1
import PackageDescription

// URLs and data sources are read through range-source's RangeSource product, which the default
// trait "Source" adds. The player finds it at runtime, so a package that turns the trait off
// (`traits: []` on its dependency on this one) gets the basic input: URLs downloaded whole.
// RANGE_SOURCE_PATH, a local range-source checkout, replaces its release.
let rangeSource: Package.Dependency = Context.environment["RANGE_SOURCE_PATH"].map { .package(path: $0) }
    ?? .package(url: "https://github.com/MediaCacheDev/range-source.git", from: "1.0.0")

let package = Package(
    name: "AlphaVideoGPU",
    platforms: [.iOS(.v13)],
    products: [
        .library(name: "AlphaVideoGPU", targets: ["AlphaVideoGPU"]),
    ],
    traits: [
        .trait(name: "Source", description: "range-source: URLs read by range and kept on disk or in memory, and app data sources"),
        .default(enabledTraits: ["Source"]),
    ],
    dependencies: [rangeSource],
    targets: [
        .target(
            name: "AlphaVideoGPU",
            dependencies: [
                .product(name: "RangeSource", package: "range-source", condition: .when(traits: ["Source"])),
            ],
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

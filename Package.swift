// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "AlphaVideoGPU",
    platforms: [.iOS(.v13)],
    products: [
        .library(name: "AlphaVideoGPU", targets: ["AlphaVideoGPU"]),
    ],
    targets: [
        .target(
            name: "AlphaVideoGPU",
            path: "ios/Sources/AlphaVideoGPU",
            // AlphaVideoGpuView.mm compiles it, and the Basis transcoder and zstd, by #include
            exclude: ["astc_decomp.cpp"],
            publicHeadersPath: "include",
            cxxSettings: [.headerSearchPath("../../../third_party/basis_universal")],
            linkerSettings: [
                .linkedFramework("Metal"),
                .linkedFramework("MetalKit"),
            ]
        ),
    ],
    cxxLanguageStandard: .cxx14
)

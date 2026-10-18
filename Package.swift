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
            // AlphaVideoGpuView.mm compiles it, and zstd, by #include; alpha_video_source.cpp does
            // the same for alpha-video-source's reader
            exclude: ["astc_decomp.cpp"],
            publicHeadersPath: "include",
            cxxSettings: [
                .headerSearchPath("../../../third_party"),
            ],
            linkerSettings: [
                .linkedFramework("Metal"),
                .linkedFramework("MetalKit"),
            ]
        ),
    ],
    cxxLanguageStandard: .cxx17
)

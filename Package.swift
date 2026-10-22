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
            // AlphaVideoGpuView.mm compiles it, and zstd, by #include; alpha_video_gpu_range_source.cpp
            // does the same for range-source's reader
            exclude: ["astc_decomp.cpp"],
            publicHeadersPath: "include",
            cxxSettings: [
                .headerSearchPath("../../../third_party"),
                .headerSearchPath("../../../third_party/range-source/include"),
            ],
            linkerSettings: [
                .linkedFramework("Metal"),
                .linkedFramework("MetalKit"),
            ]
        ),
    ],
    cxxLanguageStandard: .cxx17
)

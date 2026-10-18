# The iOS library without React Native, for CocoaPods. (React Native apps use
# react-native-alpha-video-gpu.podspec, which autolinking finds in the npm package.)
#
# 1.0.0 goes on CocoaPods trunk, which is read-only from 2 December 2026. Later versions are used
# through the podspec at their tag:
#   pod 'alpha-video-gpu', :podspec => 'https://raw.githubusercontent.com/MediaCacheDev/alpha-video-gpu/v<version>/alpha-video-gpu.podspec'

Pod::Spec.new do |s|
  s.name          = 'alpha-video-gpu'
  s.module_name   = 'AlphaVideoGPU'
  s.version       = '1.0.0'
  s.summary       = 'Transparent animations for iOS from ASTC KTX2 texture arrays, drawn with Metal.'
  s.homepage      = 'https://github.com/MediaCacheDev/alpha-video-gpu'
  s.license       = { :type => 'MIT', :file => 'LICENSE' }
  s.author        = { 'Daniel Kasprowicz' => '9932671+danielkpl2@users.noreply.github.com' }
  s.platform      = :ios, '13.0'
  s.source        = { :git => 'https://github.com/MediaCacheDev/alpha-video-gpu.git', :tag => "v#{s.version}" }

  # AlphaVideoGpuView.mm compiles the ASTC decoder and zstd by #include, and alpha_video_source.cpp
  # alpha-video-source's range reader
  s.source_files        = 'ios/Sources/AlphaVideoGPU/**/*.{h,mm}', 'ios/Sources/AlphaVideoGPU/alpha_video_source.cpp'
  s.public_header_files = 'ios/Sources/AlphaVideoGPU/include/AlphaVideoGPU.h'
  s.preserve_paths      = 'ios/Sources/AlphaVideoGPU/astc_decomp.cpp', 'third_party/zstd/**/*',
                          'third_party/alpha-video-source/**/*'
  s.frameworks          = 'Metal', 'MetalKit'
  s.libraries           = 'c++'
  s.pod_target_xcconfig = {
    'CLANG_CXX_LANGUAGE_STANDARD' => 'c++17',
    'HEADER_SEARCH_PATHS' => '"$(PODS_TARGET_SRCROOT)/third_party"'
  }
end

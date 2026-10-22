# The iOS library without React Native, for CocoaPods. (React Native apps use
# react-native-alpha-video-gpu.podspec, which autolinking finds in the npm package.)
#
#   pod 'alpha-video-gpu'                                the player and range-source
#   pod 'alpha-video-gpu', :subspecs => ['Core']         without range-source
# Source is the range-source add-on: URLs read by range and kept on disk or in memory, and app
# data sources. Without it, the basic input: URLs downloaded whole.
#
# 1.0.0 goes on CocoaPods trunk, which is read-only from 2 December 2026. Later versions are used
# through the podspec at their tag:
#   pod 'alpha-video-gpu', :podspec => 'https://raw.githubusercontent.com/MediaCacheDev/alpha-video-gpu/v<version>/alpha-video-gpu.podspec'

Pod::Spec.new do |s|
  s.name          = 'alpha-video-gpu'
  s.module_name   = 'AlphaVideoGPU'
  s.version       = '1.0.0'
  s.summary       = 'Transparent (alpha) animations for iOS from ASTC texture arrays in KTX2 files, drawn with Metal.'
  s.description   = <<~DESC
    Plays transparent animations stored as ASTC texture arrays in KTX2 files (made with
    alpha-video-gpu-encoder). The GPU samples the frames as stored, with no video decoding, so
    dozens of players run at once with little CPU. Streams files by range requests, with
    frame-exact seeking and offline storage.
  DESC
  s.homepage      = 'https://github.com/MediaCacheDev/alpha-video-gpu'
  s.license       = { :type => 'MIT', :file => 'LICENSE' }
  s.author        = { 'Daniel Kasprowicz' => '9932671+danielkpl2@users.noreply.github.com' }
  s.platform      = :ios, '13.0'
  s.source        = { :git => 'https://github.com/MediaCacheDev/alpha-video-gpu.git', :tag => "v#{s.version}" }
  s.default_subspecs = 'Core', 'Source'
  s.pod_target_xcconfig = {
    'CLANG_CXX_LANGUAGE_STANDARD' => 'c++17',
    'HEADER_SEARCH_PATHS' => '"$(PODS_TARGET_SRCROOT)/third_party" "$(PODS_TARGET_SRCROOT)/third_party/range-source/include"'
  }

  s.subspec 'Core' do |core|
    # AlphaVideoGpuView.mm compiles the ASTC decoder and zstd by #include
    core.source_files        = 'ios/Sources/AlphaVideoGPU/**/*.{h,mm}', 'third_party/range-source/include/*.h'
    core.public_header_files = 'ios/Sources/AlphaVideoGPU/include/AlphaVideoGPU.h'
    core.preserve_paths      = 'ios/Sources/AlphaVideoGPU/astc_decomp.cpp', 'third_party/zstd/**/*'
    core.frameworks          = 'Metal', 'MetalKit'
    core.libraries           = 'c++'
  end

  # The range-source add-on, found at runtime
  s.subspec 'Source' do |source|
    source.dependency 'alpha-video-gpu/Core'
    source.dependency 'range-source', '~> 1.0'
  end
end

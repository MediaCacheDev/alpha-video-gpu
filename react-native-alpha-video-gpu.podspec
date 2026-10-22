require 'json'

package = JSON.parse(File.read(File.join(__dir__, 'package.json')))

Pod::Spec.new do |s|
  s.name         = 'react-native-alpha-video-gpu'
  s.version      = package['version']
  s.summary      = package['description']
  s.homepage     = package['homepage']
  s.license      = package['license']
  s.authors      = package['author']

  s.platforms    = { :ios => '13.0' }
  s.source       = { :git => package['repository']['url'], :tag => "v#{s.version}" }

  # The iOS library (ios/Sources/AlphaVideoGPU, also the Swift package and the alpha-video-gpu
  # pod) and the React Native view. AlphaVideoGpuView.mm compiles the ASTC decoder and zstd by
  # #include, and alpha_video_gpu_range_source.cpp range-source's reader.
  s.source_files        = 'ios/Sources/AlphaVideoGPU/**/*.{h,mm}', 'ios/Sources/AlphaVideoGPU/alpha_video_gpu_range_source.cpp',
                          'react-native/ios/**/*.{h,mm}'
  s.public_header_files = 'ios/Sources/AlphaVideoGPU/include/AlphaVideoGPU.h'
  s.preserve_paths      = 'ios/Sources/AlphaVideoGPU/astc_decomp.cpp', 'third_party/zstd/**/*',
                          'third_party/range-source/**/*'
  s.frameworks          = 'Metal', 'MetalKit'
  s.libraries           = 'c++'
  s.pod_target_xcconfig = {
    'CLANG_CXX_LANGUAGE_STANDARD' => 'c++17',
    'HEADER_SEARCH_PATHS' => '"$(PODS_TARGET_SRCROOT)/third_party" "$(PODS_TARGET_SRCROOT)/third_party/range-source/include"'
  }

  # React Native's helper: the Fabric and codegen dependencies, and RCT_NEW_ARCH_ENABLED on the
  # new architecture. It reads pod_target_xcconfig, so it comes after it.
  if respond_to?(:install_modules_dependencies, true)
    install_modules_dependencies(s)
  else
    s.dependency 'React-Core'
  end
end

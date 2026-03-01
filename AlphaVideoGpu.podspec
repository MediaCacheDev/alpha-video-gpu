Pod::Spec.new do |s|
  s.name             = 'AlphaVideoGpu'
  s.version          = '1.0.0'
  s.summary          = 'GPU-accelerated transparent alpha video player'
  s.description      = <<-DESC
    GPU-accelerated transparent alpha video player for iOS using texture compression (ASTC).
    Provides smooth playback with low CPU/battery usage for animations with transparency.
                       DESC
  s.homepage         = 'https://github.com/danielkpl2/alpha-video-gpu'
  s.license          = { :type => 'MIT', :file => 'LICENSE' }
  s.author           = { 'Alpha Video' => 'contact@alphavideo.dev' }
  s.source           = { :git => 'https://github.com/danielkpl2/alpha-video-gpu.git', :tag => "#{s.version}" }

  s.ios.deployment_target = '12.0'
  s.requires_arc = true

  # Source files - paths relative to podspec location (package root)
  s.source_files = 'ios/**/*.{h,m,mm}', 'ios/vendor/basis_universal/transcoder/*.cpp', 'ios/vendor/basis_universal/zstd/*.c'
  
  # Preserve vendor paths
  s.preserve_paths = 'ios/vendor/basis_universal/**/*'
  
  s.xcconfig = {
    'HEADER_SEARCH_PATHS' => '"$(PODS_TARGET_SRCROOT)/ios" "$(PODS_TARGET_SRCROOT)/ios/vendor/basis_universal" "$(PODS_TARGET_SRCROOT)/ios/vendor/basis_universal/transcoder"',
    'CLANG_CXX_LANGUAGE_STANDARD' => 'c++14',
    'GCC_PREPROCESSOR_DEFINITIONS' => 'BASISU_NO_ITERATOR_DEBUG_LEVEL=1'
  }
  
  s.frameworks = 'Metal', 'MetalKit', 'CoreGraphics'
  
  s.dependency 'React-Core'
end

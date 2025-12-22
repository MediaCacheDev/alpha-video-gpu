Pod::Spec.new do |s|
  s.name             = 'react-native-ktx2player'
  s.version          = '1.0.0'
  s.summary          = 'KTX2 animated texture player for React Native'
  s.description      = <<-DESC
    A React Native module for playing KTX2 animated textures with 2D grid layout and multi-strip support.
    Uses Basis Universal transcoder and Metal rendering.
                       DESC
  s.homepage         = 'https://github.com/your-repo/react-native-ktx2player'
  s.license          = { :type => 'MIT', :file => 'LICENSE' }
  s.author           = { 'Your Name' => 'your.email@example.com' }
  s.source           = { :git => 'https://github.com/your-repo/react-native-ktx2player.git', :tag => "#{s.version}" }

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

module.exports = {
  dependency: {
    platforms: {
      android: {
        sourceDir: './react-native/android',
        packageImportPath: 'import dev.mediacache.alphavideo.gpu.reactnative.AlphaVideoGpuPackage;',
        packageInstance: 'new AlphaVideoGpuPackage()',
      },
      ios: {},
    },
  },
};

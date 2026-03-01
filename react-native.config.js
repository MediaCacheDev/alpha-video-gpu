module.exports = {
  dependency: {
    platforms: {
      android: {
        sourceDir: './android',
        packageImportPath: 'import com.alphavideo.gpu.AlphaVideoGpuPackage;',
        packageInstance: 'new AlphaVideoGpuPackage()',
      },
      ios: {},
    },
  },
};

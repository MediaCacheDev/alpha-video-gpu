module.exports = {
  dependency: {
    platforms: {
      android: {
        sourceDir: './android',
        packageImportPath: 'import com.ktx2player.KTX2PlayerPackage;',
        packageInstance: 'new KTX2PlayerPackage()',
      },
      ios: {
        podspecPath: './ios/KTX2Player.podspec',
      },
    },
  },
};

import { defineConfig } from 'tsup';

export default defineConfig({
  entry: { index: 'src/index.ts', react: 'src/react.tsx' },
  format: ['esm', 'cjs'],
  dts: { resolve: ['alpha-video-source'] },
  clean: true,
  target: 'es2019',
  external: ['react'],
  // alpha-video-source isn't on npm: it's bundled, so the tarball installs on its own
  noExternal: ['alpha-video-source'],
});

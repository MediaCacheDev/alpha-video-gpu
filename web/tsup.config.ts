import { defineConfig } from 'tsup';

export default defineConfig({
  entry: { index: 'src/index.ts', react: 'src/react.tsx' },
  format: ['esm', 'cjs'],
  dts: true,
  clean: true,
  target: 'es2019',
  external: ['react'],
});

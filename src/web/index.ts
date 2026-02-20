/**
 * KTX2 Player Web Core - Barrel Export
 *
 * Framework-agnostic core engine for KTX2 animated texture playback.
 * Import from here for the shared core, or use the framework-specific wrappers.
 */

export { KTX2PlayerCore } from './KTX2PlayerCore';

export type {
  FrameUV,
  Metadata,
  GLBParseResult,
  KTX2LoadEvent,
  PlayerState,
  MemoryStrategy,
  KTX2PlayerCoreOptions,
  SharedGLResources,
} from './types';

// Utilities that standalone consumers may need
export {
  isGLBContainer,
  parseGLBContainer,
  extractKTX2Metadata,
  detectTextureFormat,
  getGLCompressedFormat,
  ASTC_FORMAT_MAP,
  getASTCBlockSize,
  getASTCBytesPerLayer,
} from './ktx2-parser';

export { getCachedFile, setCachedFile } from './cache';

// Bundled WASM dependencies (Basis Universal + ZSTD, inlined)
export { initBasisTranscoder, ZSTDDecoder } from './bundled-deps';

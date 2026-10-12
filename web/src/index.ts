/**
 * alpha-video-gpu-web: KTX2 texture animations with alpha, drawn into a <canvas> with WebGL.
 * The Basis Universal transcoder and the zstd decoder (JS + WASM) are bundled inline, so there
 * are no files to host and no <script> tags.
 *
 *   import { createAlphaVideoGpuPlayer } from 'alpha-video-gpu-web';
 *
 *   const player = createAlphaVideoGpuPlayer(canvas, { autoPlay: true, loop: true });
 *   player.setSource('https://example.com/animation.ktx2');
 *
 * React: `alpha-video-gpu-web/react`.
 */

import { KTX2PlayerCore } from './KTX2PlayerCore';
import type { KTX2PlayerCoreOptions } from './types';
import { initBasisTranscoder, ZSTDDecoder } from './bundled-deps';

export { KTX2PlayerCore as AlphaVideoGpuCore };

export type {
  KTX2PlayerCoreOptions as AlphaVideoGpuOptions,
  KTX2LoadEvent as AlphaVideoGpuLoadEvent,
  PlayerState,
  MemoryStrategy,
  Metadata,
  FrameUV,
  GLBParseResult,
  SharedGLResources,
} from './types';

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

export { initBasisTranscoder, ZSTDDecoder };

/** A player on `canvas` with the bundled decoders. */
export function createAlphaVideoGpuPlayer(
  canvas: HTMLCanvasElement,
  options: Omit<KTX2PlayerCoreOptions, 'basisTranscoderInit' | 'ZSTDDecoderClass'> = {}
): KTX2PlayerCore {
  return new KTX2PlayerCore(canvas, {
    ...options,
    basisTranscoderInit: initBasisTranscoder,
    ZSTDDecoderClass: ZSTDDecoder as any,
  });
}

/**
 * alpha-video-gpu-web: transparent animations from ASTC KTX2 texture arrays, drawn into a
 * <canvas> with WebGL2. The zstd decoder (JS + WASM) is bundled inline, so there are no files to
 * host and no <script> tags.
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
import { ZSTDDecoder } from './bundled-deps';

export { KTX2PlayerCore as AlphaVideoGpuCore };

export type {
  KTX2PlayerCoreOptions as AlphaVideoGpuOptions,
  KTX2LoadEvent as AlphaVideoGpuLoadEvent,
  KTX2Source as AlphaVideoGpuSource,
  KTX2SourceOptions as AlphaVideoGpuSourceOptions,
  KTX2Storage as AlphaVideoGpuStorage,
  PlayerState,
  MemoryStrategy,
  Metadata,
  SharedGLResources,
} from './types';

export {
  extractKTX2Metadata,
  ASTC_FORMAT_MAP,
  getASTCBlockSize,
  getASTCBytesPerLayer,
} from './ktx2-parser';

export { STORAGE_NAMESPACE } from './source';
export {
  blobSource,
  createStore,
  httpSource,
  memorySource,
  HttpSourceError,
  SourceChangedError,
} from 'range-source-web';
export type { ByteSource, ByteStore, SourceInfo, StorageKind, HttpSourceOptions } from 'range-source-web';

export { AlphaVideoGpuError } from './errors';
export type { AlphaVideoGpuErrorCode } from './errors';

export { ZSTDDecoder };

/** A player on `canvas` with the bundled decoders. */
export function createAlphaVideoGpuPlayer(
  canvas: HTMLCanvasElement,
  options: Omit<KTX2PlayerCoreOptions, 'ZSTDDecoderClass'> = {}
): KTX2PlayerCore {
  return new KTX2PlayerCore(canvas, {
    ...options,
    ZSTDDecoderClass: ZSTDDecoder as any,
  });
}

/**
 * AlphaVideoGpu - Standalone Vanilla JS/TS Wrapper
 *
 * GPU-accelerated transparent alpha video player.
 * Self-contained: Basis Universal + ZSTD decoders (JS + WASM) are bundled inline.
 * No external files or <script> tags needed.
 *
 * Usage:
 *   import { createAlphaVideoGpuPlayer } from 'alpha-video-gpu/standalone';
 *
 *   const player = createAlphaVideoGpuPlayer(document.getElementById('canvas'), {
 *     autoPlay: true,
 *     loop: true,
 *   });
 *   player.setSource('https://example.com/animation.ktx2');
 *
 * Or use AlphaVideoGpuCore directly with your own decoder setup:
 *   import { AlphaVideoGpuCore } from 'alpha-video-gpu/standalone';
 */

import { KTX2PlayerCore } from '../src/web/KTX2PlayerCore';
import type { KTX2PlayerCoreOptions } from '../src/web/types';
import { initBasisTranscoder } from '../src/web/bundled-deps';
import { ZSTDDecoder } from '../src/web/bundled-deps';

// Export core engine with both new and legacy names
export { KTX2PlayerCore };
export { KTX2PlayerCore as AlphaVideoGpuCore };

export type {
  KTX2PlayerCoreOptions as AlphaVideoGpuOptions,
  KTX2PlayerCoreOptions as KTX2PlayerOptions, // Legacy alias
  KTX2LoadEvent as AlphaVideoGpuLoadEvent,
  KTX2LoadEvent, // Legacy alias
  PlayerState,
  MemoryStrategy,
  Metadata,
  FrameUV,
} from '../src/web/types';

/**
 * Create a self-contained alpha video GPU player with bundled WASM decoders.
 * This is the recommended entry point for vanilla JS/TS usage.
 */
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

// Legacy alias
export const createKTX2Player = createAlphaVideoGpuPlayer;

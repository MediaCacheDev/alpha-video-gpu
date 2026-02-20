/**
 * KTX2Player - Standalone Vanilla JS/TS Wrapper
 *
 * Self-contained: Basis Universal + ZSTD decoders (JS + WASM) are bundled inline.
 * No external files or <script> tags needed.
 *
 * Usage:
 *   import { createKTX2Player } from 'react-native-ktx2player/standalone';
 *
 *   const player = createKTX2Player(document.getElementById('canvas'), {
 *     autoPlay: true,
 *     loop: true,
 *   });
 *   player.setSource('https://example.com/animation.ktx2');
 *
 * Or use KTX2PlayerCore directly with your own decoder setup:
 *   import { KTX2PlayerCore } from 'react-native-ktx2player/standalone';
 */

import { KTX2PlayerCore } from '../src/web/KTX2PlayerCore';
import type { KTX2PlayerCoreOptions } from '../src/web/types';
import { initBasisTranscoder } from '../src/web/bundled-deps';
import { ZSTDDecoder } from '../src/web/bundled-deps';

export { KTX2PlayerCore };

export type {
  KTX2PlayerCoreOptions as KTX2PlayerOptions,
  KTX2LoadEvent,
  PlayerState,
  MemoryStrategy,
  Metadata,
  FrameUV,
} from '../src/web/types';

/**
 * Create a self-contained KTX2 player with bundled WASM decoders.
 * This is the recommended entry point for vanilla JS/TS usage.
 */
export function createKTX2Player(
  canvas: HTMLCanvasElement,
  options: Omit<KTX2PlayerCoreOptions, 'basisTranscoderInit' | 'ZSTDDecoderClass'> = {}
): KTX2PlayerCore {
  return new KTX2PlayerCore(canvas, {
    ...options,
    basisTranscoderInit: initBasisTranscoder,
    ZSTDDecoderClass: ZSTDDecoder as any,
  });
}

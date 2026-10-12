/**
 * Bundled Dependencies
 *
 * Re-exports the inlined Basis Universal transcoder and ZSTD decoder
 * so that all wrappers can import from a single location.
 *
 * Both JS and WASM are inlined — no external files or <script> tags needed.
 */

export { initBasisTranscoder, getBasisModule } from './basis-transcoder';
export { ZSTDDecoder } from './zstddec-stream-bundled.js';

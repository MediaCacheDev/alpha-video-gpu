/**
 * Bundled Dependencies
 *
 * Re-exports the inlined ZSTD decoder so that all wrappers can import from a single location.
 *
 * Both JS and WASM are inlined — no external files or <script> tags needed.
 */

export { ZSTDDecoder } from './zstddec-stream-bundled.js';

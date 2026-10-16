/**
 * - `ASTC_UNSUPPORTED`: the browser has no `WEBGL_compressed_texture_astc`, and the software
 *   decoder couldn't load either (WebAssembly blocked, as by a Content-Security-Policy without
 *   'wasm-unsafe-eval').
 * - `WEBGL_UNSUPPORTED`: no WebGL2 context could be created.
 */
export type AlphaVideoGpuErrorCode = 'ASTC_UNSUPPORTED' | 'WEBGL_UNSUPPORTED';

/** An error passed to `onError` that callers can tell apart by `code`. */
export class AlphaVideoGpuError extends Error {
  readonly code: AlphaVideoGpuErrorCode;

  constructor(code: AlphaVideoGpuErrorCode, message: string) {
    super(message);
    this.name = 'AlphaVideoGpuError';
    this.code = code;
  }
}

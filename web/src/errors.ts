/**
 * - `ASTC_UNSUPPORTED`: the browser has no `WEBGL_compressed_texture_astc` (most desktop
 *   browsers on Windows and Linux). There is no software fallback on the web.
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

export function requireASTC(gl: WebGLRenderingContext): void {
  if (gl.getExtension('WEBGL_compressed_texture_astc')) return;
  throw new AlphaVideoGpuError(
    'ASTC_UNSUPPORTED',
    'This browser has no ASTC texture support (WEBGL_compressed_texture_astc), so ASTC files cannot play here.'
  );
}

/**
 * - `ASTC_UNSUPPORTED`: the file holds native ASTC frames and the browser has no
 *   `WEBGL_compressed_texture_astc` (most desktop browsers on Windows and Linux). There is no
 *   software fallback on the web; UASTC files transcode to whatever the browser supports.
 * - `WEBGL_UNSUPPORTED`: no WebGL context could be created.
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
    'This browser has no ASTC texture support (WEBGL_compressed_texture_astc), so native ASTC files cannot play here. Use a UASTC file for browsers without ASTC.'
  );
}

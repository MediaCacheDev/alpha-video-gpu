/**
 * Software ASTC decoding, for browsers without WEBGL_compressed_texture_astc: layers are decoded
 * to RGBA in WebAssembly and drawn from an RGBA8 texture, as Android and iOS do without GPU ASTC.
 */

import { ASTC_WASM_BASE64 } from './astc-wasm';

interface AstcExports {
  memory: WebAssembly.Memory;
  _initialize(): void;
  astc_alloc(size: number): number;
  astc_free(ptr: number): void;
  astc_decode_layer(src: number, width: number, height: number, blockWidth: number, blockHeight: number, dst: number): number;
}

/** One decoder serves every player on the page, a layer at a time. */
export class SoftwareAstcDecoder {
  private _src = 0;
  private _srcSize = 0;
  private _dst = 0;
  private _dstSize = 0;

  constructor(private readonly _exports: AstcExports) {}

  /**
   * Decodes one layer of ASTC blocks to RGBA (width * height * 4 bytes). The result is a view
   * into WebAssembly memory, valid until the next call.
   */
  decode(astc: Uint8Array, width: number, height: number, blockWidth: number, blockHeight: number): Uint8Array {
    const ex = this._exports;
    if (astc.length > this._srcSize) {
      ex.astc_free(this._src);
      this._src = ex.astc_alloc(astc.length);
      this._srcSize = astc.length;
    }
    const rgbaSize = width * height * 4;
    if (rgbaSize > this._dstSize) {
      ex.astc_free(this._dst);
      this._dst = ex.astc_alloc(rgbaSize);
      this._dstSize = rgbaSize;
    }
    if (!this._src || !this._dst) throw new Error('Out of memory for software ASTC decoding');
    new Uint8Array(ex.memory.buffer, this._src, astc.length).set(astc);
    const failed = ex.astc_decode_layer(this._src, width, height, blockWidth, blockHeight, this._dst);
    if (failed > 0) console.warn(`[AlphaVideoGpu] ${failed} ASTC blocks failed to decode (drawn magenta)`);
    return new Uint8Array(ex.memory.buffer, this._dst, rgbaSize);
  }
}

let loading: Promise<SoftwareAstcDecoder> | null = null;

/** Instantiates the decoder once per page; a failed attempt is retried on the next call. */
export function loadSoftwareAstcDecoder(): Promise<SoftwareAstcDecoder> {
  if (!loading) {
    loading = (async () => {
      const bytes = Uint8Array.from(atob(ASTC_WASM_BASE64), (c) => c.charCodeAt(0));
      const { instance } = await WebAssembly.instantiate(bytes, { env: { emscripten_notify_memory_growth() {} } });
      const ex = instance.exports as unknown as AstcExports;
      ex._initialize();
      return new SoftwareAstcDecoder(ex);
    })();
    loading.catch(() => { loading = null; });
  }
  return loading;
}

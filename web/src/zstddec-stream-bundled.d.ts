/** A zstd decoder fed one input array at a time; see createStreamingDecoder. */
export interface ZSTDStreamingDecoder {
  /** Decodes the array, yielding its output in pieces. Finish iterating before the next call. */
  decode(array: Uint8Array): Generator<Uint8Array, void, undefined>;
  /** True when the input so far ends between frames. */
  finished(): boolean;
  /** Frees the WASM buffers; the decoder can't be used afterwards. */
  free(): void;
}

export declare class ZSTDDecoder {
  init(): Promise<void>;
  decode(array: Uint8Array, uncompressedSize?: number): Uint8Array;
  decodeStreaming(arrays: Iterable<Uint8Array>): Generator<Uint8Array>;
  createStreamingDecoder(): ZSTDStreamingDecoder;
}

/**
 * Decodes a KTX2 level's zstd data one layer at a time. With a seek table the decoder can start
 * at any zstd frame, so a seek decodes at most one frame's worth before the target layer;
 * without one it starts from the level's beginning.
 */

import { parseZstdSeekTable, seekPointBefore, type ZstdSeekTable } from './zstd-seek-table';

/** The streaming half of zstddec's ZSTDDecoder. */
export interface ZstdStreamingDecoder {
  decodeStreaming(arrays: Iterable<Uint8Array>): Generator<Uint8Array>;
}

/** Compressed bytes handed to the decoder at a time, so starting at a frame copies little. */
const INPUT_CHUNK_SIZE = 256 * 1024;

function* chunksFrom(data: Uint8Array, offset: number): Generator<Uint8Array> {
  for (let start = offset; start < data.length; start += INPUT_CHUNK_SIZE) {
    yield data.subarray(start, Math.min(start + INPUT_CHUNK_SIZE, data.length));
  }
}

export class ZstdLayerStream {
  readonly seekTable: ZstdSeekTable | null;
  private _generator: Generator<Uint8Array> | null = null;
  private _chunk: Uint8Array = new Uint8Array(0);
  private _chunkPos = 0;
  // Decompressed bytes taken from the decoder, counted from the level's start
  private _decoded = 0;
  private _skipBytes = 0;
  private _position = 0;

  constructor(
    private readonly _decoder: ZstdStreamingDecoder,
    private readonly _level: Uint8Array,
    private readonly _bytesPerLayer: number,
    decompressedLength?: number,
  ) {
    this.seekTable = parseZstdSeekTable(_level, decompressedLength);
    this._startAt(0, 0);
  }

  /** The layer readLayer returns next. */
  get position(): number {
    return this._position;
  }

  /** True while decoded bytes before position still have to be thrown away. */
  get skipping(): boolean {
    return this._skipBytes > 0;
  }

  /**
   * Makes layer the next one read. Decoding carries on from where it is when that is no further
   * from the target than the nearest seek point, and starts at that seek point otherwise.
   */
  moveTo(layer: number): void {
    const target = layer * this._bytesPerLayer;
    const point = seekPointBefore(this.seekTable, target);
    if (this._decoded < point.decompressedOffset || this._decoded > target) {
      this._startAt(point.compressedOffset, point.decompressedOffset);
    }
    this._skipBytes = target - this._decoded;
    this._position = layer;
  }

  /** Throws away decoded bytes toward position until done or the deadline (performance.now()). */
  skip(deadline = Infinity): void {
    while (this._skipBytes > 0 && performance.now() < deadline) {
      if (!this._fillChunk()) return;
      const taken = Math.min(this._skipBytes, this._chunk.length - this._chunkPos);
      this._chunkPos += taken;
      this._decoded += taken;
      this._skipBytes -= taken;
    }
  }

  /** The layer at position, or null past the end of the data. */
  readLayer(): Uint8Array | null {
    this.skip();
    if (this._skipBytes > 0) return null;
    const layer = new Uint8Array(this._bytesPerLayer);
    let filled = 0;
    while (filled < layer.length) {
      if (!this._fillChunk()) return null;
      const taken = Math.min(layer.length - filled, this._chunk.length - this._chunkPos);
      layer.set(this._chunk.subarray(this._chunkPos, this._chunkPos + taken), filled);
      this._chunkPos += taken;
      filled += taken;
    }
    this._decoded += layer.length;
    this._position++;
    return layer;
  }

  /** Frees the decoder's WASM buffers. */
  close(): void {
    try {
      this._generator?.return(undefined);
    } catch { /* already closed */ }
    this._generator = null;
  }

  private _startAt(compressedOffset: number, decompressedOffset: number): void {
    this.close();
    this._generator = this._decoder.decodeStreaming(chunksFrom(this._level, compressedOffset));
    this._chunk = new Uint8Array(0);
    this._chunkPos = 0;
    this._decoded = decompressedOffset;
  }

  /** False when the decoder has no more output. */
  private _fillChunk(): boolean {
    while (this._chunkPos >= this._chunk.length) {
      const next = this._generator?.next();
      if (!next || next.done) return false;
      this._chunk = next.value;
      this._chunkPos = 0;
    }
    return true;
  }
}

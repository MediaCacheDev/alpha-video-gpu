/**
 * Decodes a KTX2 level's zstd data one layer at a time. With a seek table the decoder can start
 * at any zstd frame, so a seek decodes at most one frame's worth before the target layer;
 * without one it starts from the level's beginning.
 */

import type { LayerStream, LevelData } from './layer-stream';
import { readZstdSeekTable, seekPointBefore, type ZstdSeekTable } from './zstd-seek-table';
import type { ZSTDStreamingDecoder } from './zstddec-stream-bundled';

/**
 * The streaming half of a zstd decoder. With createStreamingDecoder the level is read as it
 * decodes; a decoder with only decodeStreaming gets the whole level read first.
 */
export interface ZstdStreamingDecoder {
  decodeStreaming(arrays: Iterable<Uint8Array>): Generator<Uint8Array>;
  createStreamingDecoder?(): ZSTDStreamingDecoder;
}

/** Compressed bytes handed to the decoder at a time, so starting at a frame reads and copies little. */
const INPUT_CHUNK_SIZE = 256 * 1024;

/** Decoder output, in the pieces the decoder hands it out. */
interface OutputPump {
  /** The next piece, or null at the end of the level or once closed. */
  next(): Promise<Uint8Array | null>;
  close(): void;
}

/** Feeds the decoder from the level as it goes, starting at a frame. */
class PushPump implements OutputPump {
  private readonly _decoder: ZSTDStreamingDecoder;
  private _output: Generator<Uint8Array, void, undefined> | null = null;
  private readonly _controller = new AbortController();
  private _closed = false;

  constructor(decoder: ZstdStreamingDecoder, private readonly _level: LevelData, private _inputPos: number) {
    this._decoder = decoder.createStreamingDecoder!();
  }

  async next(): Promise<Uint8Array | null> {
    for (;;) {
      if (this._closed) return null;
      if (this._output) {
        const piece = this._output.next();
        if (!piece.done) return piece.value;
        this._output = null;
      }
      if (this._inputPos >= this._level.length) {
        if (!this._decoder.finished()) throw new Error('zstd: the level ends partway through a frame');
        return null;
      }
      const length = Math.min(INPUT_CHUNK_SIZE, this._level.length - this._inputPos);
      let input: Uint8Array;
      try {
        input = await this._level.read(this._inputPos, length, this._controller.signal);
      } catch (error) {
        if (this._closed) return null;
        throw error;
      }
      if (this._closed) return null;
      if (input.byteLength === 0) throw new Error(`the level ends at ${this._inputPos} of ${this._level.length} bytes`);
      this._inputPos += input.byteLength;
      this._output = this._decoder.decode(input);
    }
  }

  close(): void {
    if (this._closed) return;
    this._closed = true;
    this._controller.abort();
    try {
      this._output?.return(undefined);
    } catch { /* already finished */ }
    this._decoder.free();
  }
}

/** For decoders without createStreamingDecoder: decodes a level already read whole. */
class WholeLevelPump implements OutputPump {
  private _generator: Generator<Uint8Array> | null;

  constructor(decoder: ZstdStreamingDecoder, level: Uint8Array, offset: number) {
    this._generator = decoder.decodeStreaming(chunksFrom(level, offset));
  }

  async next(): Promise<Uint8Array | null> {
    const piece = this._generator?.next();
    return !piece || piece.done ? null : piece.value;
  }

  close(): void {
    try {
      this._generator?.return(undefined);
    } catch { /* already closed */ }
    this._generator = null;
  }
}

function* chunksFrom(data: Uint8Array, offset: number): Generator<Uint8Array> {
  for (let start = offset; start < data.length; start += INPUT_CHUNK_SIZE) {
    yield data.subarray(start, Math.min(start + INPUT_CHUNK_SIZE, data.length));
  }
}

export class ZstdLayerStream implements LayerStream {
  private _pump: OutputPump | null = null;
  private _chunk: Uint8Array = new Uint8Array(0);
  private _chunkPos = 0;
  // Decompressed bytes taken from the decoder, counted from the level's start
  private _decoded = 0;
  private _skipBytes = 0;
  private _position = 0;
  // Changes on every moveTo and close, so a read they interrupt can tell
  private _epoch = 0;
  private _atEnd = false;

  /** Reads the level's seek table (and the whole level, for a decoder that needs it), then starts at layer 0. */
  static async open(
    decoder: ZstdStreamingDecoder,
    level: LevelData,
    bytesPerLayer: number,
    decompressedLength?: number,
  ): Promise<ZstdLayerStream> {
    const seekTable = await readZstdSeekTable((offset, length) => level.read(offset, length), level.length, decompressedLength);
    const whole = decoder.createStreamingDecoder ? null : await level.read(0, level.length);
    return new ZstdLayerStream(decoder, level, bytesPerLayer, seekTable, whole);
  }

  private constructor(
    private readonly _decoder: ZstdStreamingDecoder,
    private readonly _level: LevelData,
    private readonly _bytesPerLayer: number,
    readonly seekTable: ZstdSeekTable | null,
    private readonly _wholeLevel: Uint8Array | null,
  ) {
    this._startAt(0, 0);
  }

  get position(): number {
    return this._position;
  }

  get skipping(): boolean {
    return this._skipBytes > 0;
  }

  get atEnd(): boolean {
    return this._atEnd;
  }

  /**
   * Makes layer the next one read. Decoding carries on from where it is when that is no further
   * from the target than the nearest seek point, and starts at that seek point otherwise.
   */
  moveTo(layer: number): void {
    this._epoch++;
    this._atEnd = false;
    const target = layer * this._bytesPerLayer;
    const point = seekPointBefore(this.seekTable, target);
    if (this._decoded < point.decompressedOffset || this._decoded > target) {
      this._startAt(point.compressedOffset, point.decompressedOffset);
    }
    this._skipBytes = target - this._decoded;
    this._position = layer;
  }

  /** Throws away decoded bytes toward position until done or the deadline (performance.now()). */
  async skip(deadline = Infinity): Promise<void> {
    while (this._skipBytes > 0 && performance.now() < deadline) {
      if (!(await this._fillChunk())) return;
      const taken = Math.min(this._skipBytes, this._chunk.length - this._chunkPos);
      this._chunkPos += taken;
      this._decoded += taken;
      this._skipBytes -= taken;
    }
  }

  async readLayer(): Promise<Uint8Array | null> {
    const epoch = this._epoch;
    await this.skip();
    if (epoch !== this._epoch) return null;
    if (this._skipBytes > 0) {
      this._atEnd = true;
      return null;
    }
    const layer = new Uint8Array(this._bytesPerLayer);
    let filled = 0;
    while (filled < layer.length) {
      const more = await this._fillChunk();
      if (epoch !== this._epoch) return null;
      if (!more) {
        this._atEnd = true;
        return null;
      }
      const taken = Math.min(layer.length - filled, this._chunk.length - this._chunkPos);
      layer.set(this._chunk.subarray(this._chunkPos, this._chunkPos + taken), filled);
      this._chunkPos += taken;
      this._decoded += taken;
      filled += taken;
    }
    this._position++;
    return layer;
  }

  /** Frees the decoder's WASM buffers and stops reading. */
  close(): void {
    this._epoch++;
    this._pump?.close();
    this._pump = null;
  }

  private _startAt(compressedOffset: number, decompressedOffset: number): void {
    this._pump?.close();
    this._pump = this._wholeLevel
      ? new WholeLevelPump(this._decoder, this._wholeLevel, compressedOffset)
      : new PushPump(this._decoder, this._level, compressedOffset);
    this._chunk = new Uint8Array(0);
    this._chunkPos = 0;
    this._decoded = decompressedOffset;
  }

  /** False when the decoder has no more output, or its pump was replaced or closed meanwhile. */
  private async _fillChunk(): Promise<boolean> {
    while (this._chunkPos >= this._chunk.length) {
      const pump = this._pump;
      if (!pump) return false;
      const piece = await pump.next();
      if (pump !== this._pump || !piece) return false;
      this._chunk = piece;
      this._chunkPos = 0;
    }
    return true;
  }
}

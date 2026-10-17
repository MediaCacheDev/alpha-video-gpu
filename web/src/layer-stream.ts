/**
 * Streaming playback reads a KTX2 level one layer (frame) at a time through a LayerStream,
 * from a level that is read by offset, so a file can play while it is still arriving.
 */

/** A level's bytes, read by offset from the level's start. */
export interface LevelData {
  readonly length: number;
  read(offset: number, length: number, signal?: AbortSignal): Promise<Uint8Array>;
}

export interface LayerStream {
  /** The layer readLayer returns next. */
  readonly position: number;
  /** True while bytes before position still have to be decoded and thrown away. */
  readonly skipping: boolean;
  /** True once a read found no more layers. */
  readonly atEnd: boolean;
  /** Makes layer the next one read; a read in progress then returns null. */
  moveTo(layer: number): void;
  /** Works toward position until there or the deadline (performance.now()). */
  skip(deadline?: number): Promise<void>;
  /** The layer at position; null past the end, or when moveTo or close interrupted the read. */
  readLayer(): Promise<Uint8Array | null>;
  close(): void;
}

/** Layers of an uncompressed level, read straight from where each one sits. */
export class UncompressedLayerStream implements LayerStream {
  private _position = 0;
  private _epoch = 0;
  private _atEnd = false;
  private _controller = new AbortController();

  constructor(
    private readonly _level: LevelData,
    private readonly _bytesPerLayer: number,
    private readonly _layerCount: number,
  ) {}

  get position(): number {
    return this._position;
  }

  get skipping(): boolean {
    return false;
  }

  get atEnd(): boolean {
    return this._atEnd;
  }

  moveTo(layer: number): void {
    this._epoch++;
    this._position = layer;
    this._atEnd = false;
  }

  async skip(): Promise<void> {}

  async readLayer(): Promise<Uint8Array | null> {
    const layer = this._position;
    if (layer >= this._layerCount) {
      this._atEnd = true;
      return null;
    }
    const epoch = this._epoch;
    const data = await this._level.read(layer * this._bytesPerLayer, this._bytesPerLayer, this._controller.signal);
    if (epoch !== this._epoch) return null;
    if (data.byteLength < this._bytesPerLayer) {
      this._atEnd = true;
      return null;
    }
    this._position = layer + 1;
    return data;
  }

  close(): void {
    this._epoch++;
    this._controller.abort();
  }
}

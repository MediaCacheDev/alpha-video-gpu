import fs from 'node:fs';
import path from 'node:path';
import { beforeAll, describe, expect, it } from 'vitest';
import { ZSTDDecoder } from '../src/zstddec-stream-bundled.js';
import { ZstdLayerStream, type ZstdStreamingDecoder } from '../src/zstd-layer-stream';
import { UncompressedLayerStream, type LevelData } from '../src/layer-stream';
import { parseZstdSeekTable, readZstdSeekTable, seekPointBefore } from '../src/zstd-seek-table';

/** The level as LevelData, optionally slow, with every read's offset recorded. */
function levelData(level: Uint8Array, options: { delayMs?: number; reads?: number[] } = {}): LevelData {
  return {
    length: level.length,
    async read(offset, length, signal) {
      options.reads?.push(offset);
      if (options.delayMs) await new Promise((resolve) => setTimeout(resolve, options.delayMs));
      signal?.throwIfAborted();
      return level.subarray(offset, Math.min(offset + length, level.length));
    },
  };
}

const fixturesDir = path.resolve(__dirname, '../../tests/fixtures');

/** A seek table as texenc writes it: [compressed, decompressed] per frame, then the footer. */
function seekTable(frames: [number, number][], checksums = false): Uint8Array {
  const entrySize = checksums ? 12 : 8;
  const tableSize = frames.length * entrySize + 9;
  const table = new Uint8Array(8 + tableSize);
  const view = new DataView(table.buffer);
  view.setUint32(0, 0x184d2a5e, true);
  view.setUint32(4, tableSize, true);
  frames.forEach(([compressed, decompressed], i) => {
    view.setUint32(8 + i * entrySize, compressed, true);
    view.setUint32(12 + i * entrySize, decompressed, true);
  });
  view.setUint32(8 + frames.length * entrySize, frames.length, true);
  table[8 + frames.length * entrySize + 4] = checksums ? 0x80 : 0;
  view.setUint32(8 + frames.length * entrySize + 5, 0x8f92eab1, true);
  return table;
}

/** Frame bytes (contents don't matter to the parser) followed by the table. */
function levelWith(frames: [number, number][], checksums = false): Uint8Array {
  const framesSize = frames.reduce((sum, [compressed]) => sum + compressed, 0);
  const table = seekTable(frames, checksums);
  const level = new Uint8Array(framesSize + table.length);
  level.set(table, framesSize);
  return level;
}

describe('parseZstdSeekTable', () => {
  const frames: [number, number][] = [[100, 4000], [50, 4000], [70, 2000]];

  it('reads where each frame starts', () => {
    expect(parseZstdSeekTable(levelWith(frames))).toEqual({
      compressedOffsets: [0, 100, 150],
      decompressedOffsets: [0, 4000, 8000],
    });
  });

  it('reads tables with checksums', () => {
    expect(parseZstdSeekTable(levelWith(frames, true))?.compressedOffsets).toEqual([0, 100, 150]);
  });

  it('checks the decompressed length when given', () => {
    expect(parseZstdSeekTable(levelWith(frames), 10000)).not.toBeNull();
    expect(parseZstdSeekTable(levelWith(frames), 10001)).toBeNull();
  });

  it('rejects tables that do not describe the data before them', () => {
    const level = levelWith(frames);
    expect(parseZstdSeekTable(level.subarray(1))).toBeNull();  // frames don't add up
    const reserved = levelWith(frames);
    reserved[reserved.length - 5] = 0x04;
    expect(parseZstdSeekTable(reserved)).toBeNull();
    const wrongMagic = levelWith(frames);
    wrongMagic[frames.reduce((s, [c]) => s + c, 0)] ^= 1;
    expect(parseZstdSeekTable(wrongMagic)).toBeNull();
    expect(parseZstdSeekTable(levelWith([]))).toBeNull();
    expect(parseZstdSeekTable(new Uint8Array(8))).toBeNull();
  });

  it('reads a table from just the level tail, given where the tail starts', () => {
    const level = levelWith(frames);
    expect(parseZstdSeekTable(level.subarray(220), undefined, 220)?.compressedOffsets).toEqual([0, 100, 150]);
    expect(parseZstdSeekTable(level.subarray(220))).toBeNull();
  });
});

describe('readZstdSeekTable', () => {
  const frames: [number, number][] = [[100, 4000], [50, 4000], [70, 2000]];

  it('reads the footer, then only the table', async () => {
    const level = levelWith(frames);
    const reads: Array<[number, number]> = [];
    const read = async (offset: number, length: number) => {
      reads.push([offset, length]);
      return level.subarray(offset, offset + length);
    };
    expect((await readZstdSeekTable(read, level.length, 10000))?.decompressedOffsets).toEqual([0, 4000, 8000]);
    expect(reads).toEqual([[level.length - 9, 9], [220, level.length - 220]]);
  });

  it('reads only the footer of a level without a table', async () => {
    const level = new Uint8Array(1000);
    let reads = 0;
    const read = async (offset: number, length: number) => {
      reads++;
      return level.subarray(offset, offset + length);
    };
    expect(await readZstdSeekTable(read, level.length)).toBeNull();
    expect(reads).toBe(1);
  });
});

describe('seekPointBefore', () => {
  const table = { compressedOffsets: [0, 100, 150], decompressedOffsets: [0, 4000, 8000] };

  it('finds the last frame starting at or before the offset', () => {
    expect(seekPointBefore(table, 0)).toEqual({ compressedOffset: 0, decompressedOffset: 0 });
    expect(seekPointBefore(table, 3999)).toEqual({ compressedOffset: 0, decompressedOffset: 0 });
    expect(seekPointBefore(table, 4000)).toEqual({ compressedOffset: 100, decompressedOffset: 4000 });
    expect(seekPointBefore(table, 9999)).toEqual({ compressedOffset: 150, decompressedOffset: 8000 });
  });

  it('is the level start without a table', () => {
    expect(seekPointBefore(null, 123456)).toEqual({ compressedOffset: 0, decompressedOffset: 0 });
  });
});

interface Fixture {
  name: string;
  level: Uint8Array;
  bytesPerLayer: number;
  layers: number;
  seekPoints?: number;
}

function loadFixture(name: string, e: { bytesPerLayer: number; layers: number; seekPoints?: number }): Fixture {
  const data = new Uint8Array(fs.readFileSync(path.join(fixturesDir, name)));
  const view = new DataView(data.buffer, data.byteOffset, data.byteLength);
  const offset = Number(view.getBigUint64(80, true));
  const length = Number(view.getBigUint64(88, true));
  return { name, level: data.subarray(offset, offset + length), ...e };
}

const expected = JSON.parse(fs.readFileSync(path.join(fixturesDir, 'expected.json'), 'utf8'));
const zstdFixtures: Fixture[] = Object.entries(expected)
  .filter(([name, e]: [string, any]) => name !== '_comment' && e.supercompression === 2 && /astc4x4(_seek)?\.ktx2$/.test(name))
  .map(([name, e]: [string, any]) => loadFixture(name, e));

/** Records where in the level each decoder run starts: the first level read after it is created. */
function recordingRuns(decoder: ZstdStreamingDecoder, level: Uint8Array, starts: number[]) {
  let started = false;
  const data = levelData(level);
  return {
    decoder: {
      decodeStreaming: (chunks: Iterable<Uint8Array>) => decoder.decodeStreaming(chunks),
      createStreamingDecoder: () => {
        started = true;
        return decoder.createStreamingDecoder!();
      },
    } as ZstdStreamingDecoder,
    level: {
      length: data.length,
      read(offset: number, length: number, signal?: AbortSignal) {
        if (started) starts.push(offset);
        started = false;
        return data.read(offset, length, signal);
      },
    } as LevelData,
  };
}

/** Byte equality without vitest's per-element diffing, which takes seconds on whole layers. */
function sameBytes(a: Uint8Array | null, b: Uint8Array): boolean {
  return a !== null && Buffer.compare(a, b) === 0;
}

describe.each(zstdFixtures)('ZstdLayerStream on $name', (fixture) => {
  let decoder: InstanceType<typeof ZSTDDecoder>;
  let whole: Uint8Array;
  const layerAt = (i: number) => whole.subarray(i * fixture.bytesPerLayer, (i + 1) * fixture.bytesPerLayer);

  beforeAll(async () => {
    decoder = new ZSTDDecoder();
    await decoder.init();
    whole = decoder.decode(fixture.level, fixture.bytesPerLayer * fixture.layers);
  });

  it(fixture.seekPoints ? `finds ${fixture.seekPoints} seek points` : 'has no seek table', () => {
    const table = parseZstdSeekTable(fixture.level, fixture.bytesPerLayer * fixture.layers);
    expect(table?.compressedOffsets.length).toBe(fixture.seekPoints);
  });

  const open = (level: LevelData = levelData(fixture.level), withDecoder: ZstdStreamingDecoder = decoder) =>
    ZstdLayerStream.open(withDecoder, level, fixture.bytesPerLayer, fixture.bytesPerLayer * fixture.layers);

  it('reads every layer in order, then nothing', async () => {
    const stream = await open();
    for (let i = 0; i < fixture.layers; i++) expect(sameBytes(await stream.readLayer(), layerAt(i))).toBe(true);
    expect(stream.atEnd).toBe(false);
    expect(await stream.readLayer()).toBeNull();
    expect(stream.atEnd).toBe(true);
    stream.close();
  });

  it('reads the right layer after seeks in any direction', async () => {
    const stream = await open();
    for (const target of [37, 38, 59, 1, 29, 30, 31, 0, 45, 44, 59, 0]) {
      stream.moveTo(target);
      expect(stream.position).toBe(target);
      expect(sameBytes(await stream.readLayer(), layerAt(target))).toBe(true);
    }
    stream.close();
  });

  it('skips in slices that stop at the deadline', async () => {
    const stream = await open();
    stream.moveTo(fixture.layers - 1);
    await stream.skip(0);  // a deadline already past does nothing
    expect(stream.skipping).toBe(fixture.layers - 1 > 0);
    await stream.skip();
    expect(stream.skipping).toBe(false);
    expect(sameBytes(await stream.readLayer(), layerAt(fixture.layers - 1))).toBe(true);
    stream.close();
  });

  it('starts at the nearest seek point, or carries on when that is no further', async () => {
    const starts: number[] = [];
    const runs = recordingRuns(decoder, fixture.level, starts);
    const stream = await open(runs.level, runs.decoder);
    const table = stream.seekTable;
    const pointFor = (layer: number) => seekPointBefore(table, layer * fixture.bytesPerLayer).compressedOffset;

    await stream.readLayer();       // decoder now at layer 1
    stream.moveTo(2);               // a step ahead: carry on
    await stream.readLayer();
    stream.moveTo(50);              // far ahead: the seek point (or carry on, with one stream)
    await stream.readLayer();
    stream.moveTo(10);              // behind: restart
    await stream.readLayer();
    expect(starts).toEqual(table ? [0, pointFor(50), pointFor(10)] : [0, 0]);
    stream.close();
  });

  it('returns null from a read that a seek interrupts, then reads the new target', async () => {
    const stream = await open(levelData(fixture.level, { delayMs: 2 }));
    const interrupted = stream.readLayer();
    stream.moveTo(40);
    expect(await interrupted).toBeNull();
    expect(stream.atEnd).toBe(false);
    expect(stream.position).toBe(40);
    expect(sameBytes(await stream.readLayer(), layerAt(40))).toBe(true);
    stream.close();
  });

  it('returns null from a read that close interrupts', async () => {
    const stream = await open(levelData(fixture.level, { delayMs: 2 }));
    const interrupted = stream.readLayer();
    stream.close();
    expect(await interrupted).toBeNull();
  });

  it('reads the whole level first for a decoder without createStreamingDecoder', async () => {
    const reads: number[] = [];
    const pullOnly: ZstdStreamingDecoder = { decodeStreaming: (chunks) => decoder.decodeStreaming(chunks) };
    const stream = await open(levelData(fixture.level, { reads }), pullOnly);
    const readsAtOpen = reads.length;
    stream.moveTo(33);
    expect(sameBytes(await stream.readLayer(), layerAt(33))).toBe(true);
    expect(reads.length).toBe(readsAtOpen);
    stream.close();
  });
});

describe('UncompressedLayerStream', () => {
  const bytesPerLayer = 64;
  const layers = 10;
  const level = Uint8Array.from({ length: bytesPerLayer * layers }, (_, i) => Math.floor(i / bytesPerLayer));

  it('reads each layer from where it sits, after seeks too', async () => {
    const stream = new UncompressedLayerStream(levelData(level), bytesPerLayer, layers);
    expect((await stream.readLayer())![0]).toBe(0);
    stream.moveTo(7);
    expect((await stream.readLayer())![0]).toBe(7);
    expect(stream.position).toBe(8);
    stream.moveTo(9);
    await stream.readLayer();
    expect(await stream.readLayer()).toBeNull();
    expect(stream.atEnd).toBe(true);
  });

  it('returns null from a read that a seek interrupts', async () => {
    const stream = new UncompressedLayerStream(levelData(level, { delayMs: 2 }), bytesPerLayer, layers);
    const interrupted = stream.readLayer();
    stream.moveTo(5);
    expect(await interrupted).toBeNull();
    expect((await stream.readLayer())![0]).toBe(5);
  });
});

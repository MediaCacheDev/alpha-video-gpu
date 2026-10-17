import fs from 'node:fs';
import path from 'node:path';
import { beforeAll, describe, expect, it } from 'vitest';
import { ZSTDDecoder } from '../src/zstddec-stream-bundled.js';
import { ZstdLayerStream, type ZstdStreamingDecoder } from '../src/zstd-layer-stream';
import { parseZstdSeekTable, seekPointBefore } from '../src/zstd-seek-table';

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

/** Records where in the level each decoder run starts. */
function recordingDecoder(decoder: ZstdStreamingDecoder, level: Uint8Array, starts: number[]): ZstdStreamingDecoder {
  return {
    decodeStreaming(chunks) {
      const iterator = chunks[Symbol.iterator]();
      const first = iterator.next();
      if (!first.done) starts.push(first.value.byteOffset - level.byteOffset);
      return decoder.decodeStreaming((function* () {
        if (!first.done) yield first.value;
        yield* { [Symbol.iterator]: () => iterator };
      })());
    },
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

  it('reads every layer in order, then nothing', () => {
    const stream = new ZstdLayerStream(decoder, fixture.level, fixture.bytesPerLayer);
    for (let i = 0; i < fixture.layers; i++) expect(sameBytes(stream.readLayer(), layerAt(i))).toBe(true);
    expect(stream.readLayer()).toBeNull();
    stream.close();
  });

  it('reads the right layer after seeks in any direction', () => {
    const stream = new ZstdLayerStream(decoder, fixture.level, fixture.bytesPerLayer);
    for (const target of [37, 38, 59, 1, 29, 30, 31, 0, 45, 44, 59, 0]) {
      stream.moveTo(target);
      expect(stream.position).toBe(target);
      expect(sameBytes(stream.readLayer(), layerAt(target))).toBe(true);
    }
    stream.close();
  });

  it('skips in slices that stop at the deadline', () => {
    const stream = new ZstdLayerStream(decoder, fixture.level, fixture.bytesPerLayer);
    stream.moveTo(fixture.layers - 1);
    stream.skip(0);  // a deadline already past does nothing
    expect(stream.skipping).toBe(fixture.layers - 1 > 0);
    stream.skip();
    expect(stream.skipping).toBe(false);
    expect(sameBytes(stream.readLayer(), layerAt(fixture.layers - 1))).toBe(true);
    stream.close();
  });

  it('starts at the nearest seek point, or carries on when that is no further', () => {
    const starts: number[] = [];
    const stream = new ZstdLayerStream(recordingDecoder(decoder, fixture.level, starts), fixture.level, fixture.bytesPerLayer);
    const table = stream.seekTable;
    const pointFor = (layer: number) => seekPointBefore(table, layer * fixture.bytesPerLayer).compressedOffset;

    stream.readLayer();             // decoder now at layer 1
    stream.moveTo(2);               // a step ahead: carry on
    stream.readLayer();
    stream.moveTo(50);              // far ahead: the seek point (or carry on, with one stream)
    stream.readLayer();
    stream.moveTo(10);              // behind: restart
    stream.readLayer();
    expect(starts).toEqual(table ? [0, pointFor(50), pointFor(10)] : [0, 0]);
    stream.close();
  });
});

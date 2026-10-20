import fs from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  ASTC_FORMAT_MAP,
  extractKTX2Metadata,
  getASTCBlockSize,
  getASTCBytesPerLayer,
} from '../src/ktx2-parser';

const fixturesDir = path.resolve(__dirname, '../../tests/fixtures');

interface Expected {
  width?: number;
  height?: number;
  layers?: number;
  vkFormat?: number;
  block?: [number, number];
  glFormat?: number;
  supercompression?: number;
  bytesPerLayer?: number;
  metadata: Record<string, unknown>;
}

const expected: Record<string, Expected> = JSON.parse(fs.readFileSync(path.join(fixturesDir, 'expected.json'), 'utf8'));
delete expected._comment;

function load(name: string): Uint8Array {
  return new Uint8Array(fs.readFileSync(path.join(fixturesDir, name)));
}

function header(data: Uint8Array) {
  const view = new DataView(data.buffer, data.byteOffset, data.byteLength);
  return {
    vkFormat: view.getUint32(12, true),
    width: view.getUint32(20, true),
    height: view.getUint32(24, true),
    layers: view.getUint32(32, true),
    levels: view.getUint32(40, true),
    supercompression: view.getUint32(44, true),
    level0UncompressedLength: Number(view.getBigUint64(80 + 16, true)),
  };
}

// The Vulkan ASTC formats in VkFormat order (157-184), each an UNORM/SRGB pair.
const VK_ASTC_BLOCKS: [number, number][] = [
  [4, 4], [5, 4], [5, 5], [6, 5], [6, 6], [8, 5], [8, 6], [8, 8], [10, 5], [10, 6], [10, 8], [10, 10], [12, 10], [12, 12],
];

describe('ASTC format tables', () => {
  VK_ASTC_BLOCKS.forEach(([w, h], i) => {
    const unorm = 157 + 2 * i;
    const srgb = unorm + 1;
    it(`VkFormat ${unorm}/${srgb} is ${w}x${h}`, () => {
      expect(getASTCBlockSize(unorm)).toEqual({ blockWidth: w, blockHeight: h });
      expect(getASTCBlockSize(srgb)).toEqual({ blockWidth: w, blockHeight: h });
      // COMPRESSED_RGBA_ASTC_*_KHR and COMPRESSED_SRGB8_ALPHA8_ASTC_*_KHR, in the same order.
      expect(ASTC_FORMAT_MAP[unorm]).toBe(0x93b0 + i);
      expect(ASTC_FORMAT_MAP[srgb]).toBe(0x93d0 + i);
    });
  });

  it('rounds partial blocks up on odd sizes', () => {
    expect(getASTCBytesPerLayer(383, 217, 157)).toBe(96 * 55 * 16);
    expect(getASTCBytesPerLayer(1, 1, 183)).toBe(16);
    expect(getASTCBytesPerLayer(13, 13, 183)).toBe(2 * 2 * 16);
  });
});

describe.each(Object.entries(expected))('%s', (name, e) => {
  const data = load(name);
  const h = header(data);

  it('has the header the encoder was asked for', () => {
    expect(h).toMatchObject({ width: e.width, height: e.height, layers: e.layers, vkFormat: e.vkFormat, levels: 1, supercompression: e.supercompression });
  });

  it('carries the player metadata', () => {
    expect(extractKTX2Metadata(data)).toMatchObject(e.metadata);
  });

  it('holds bytesPerLayer per layer', () => {
    expect(h.level0UncompressedLength).toBe(e.bytesPerLayer! * Math.max(1, e.layers!));
  });

  if (e.block) {
    it('maps to the right block size, upload size and linear (non-sRGB) GL format', () => {
      expect(getASTCBlockSize(h.vkFormat)).toEqual({ blockWidth: e.block![0], blockHeight: e.block![1] });
      expect(getASTCBytesPerLayer(h.width, h.height, h.vkFormat)).toBe(e.bytesPerLayer);
      expect(ASTC_FORMAT_MAP[h.vkFormat]).toBe(e.glFormat);
    });
  }
});

/** A 400x400, 181-layer ASTC 4x4 header followed by the given key/value entries (no level data). */
function ktx2WithKeys(entries: [string, Uint8Array][]): Uint8Array {
  const encoded = entries.map(([key, value]) => {
    const keyBytes = new TextEncoder().encode(`${key}\0`);
    const length = keyBytes.length + value.length;
    const entry = new Uint8Array(4 + length + ((4 - (length % 4)) % 4));
    new DataView(entry.buffer).setUint32(0, length, true);
    entry.set(keyBytes, 4);
    entry.set(value, 4 + keyBytes.length);
    return entry;
  });
  const kvdLength = encoded.reduce((sum, e) => sum + e.length, 0);
  const data = new Uint8Array(80 + kvdLength);
  data.set([0xab, 0x4b, 0x54, 0x58, 0x20, 0x32, 0x30, 0xbb, 0x0d, 0x0a, 0x1a, 0x0a]);
  const view = new DataView(data.buffer);
  view.setUint32(12, 157, true);
  view.setUint32(20, 400, true);
  view.setUint32(24, 400, true);
  view.setUint32(32, 181, true);
  view.setUint32(56, 80, true);
  view.setUint32(60, kvdLength, true);
  let pos = 80;
  for (const entry of encoded) {
    data.set(entry, pos);
    pos += entry.length;
  }
  return data;
}

function animData(duration: number, timescale: number): Uint8Array {
  const value = new Uint8Array(12);
  new DataView(value.buffer).setUint32(0, duration, true);
  new DataView(value.buffer).setUint32(4, timescale, true);
  return value;
}

describe('frame rate from KTXanimData', () => {
  it('keeps fractional rates exact', () => {
    const data = ktx2WithKeys([['KTXanimData', animData(1001, 30000)], ['KTXwriter', new TextEncoder().encode('test\0')]]);
    expect(extractKTX2Metadata(data)).toEqual({ frameWidth: 400, frameHeight: 400, frameCount: 181, frameRate: 30000 / 1001 });
  });

  it('plays files without it at 30 fps', () => {
    expect(extractKTX2Metadata(ktx2WithKeys([['KTXwriter', new TextEncoder().encode('test\0')]]))?.frameRate).toBe(30);
    expect(extractKTX2Metadata(ktx2WithKeys([]))?.frameRate).toBe(30);
  });

  it('ignores a zero duration or timescale', () => {
    expect(extractKTX2Metadata(ktx2WithKeys([['KTXanimData', animData(0, 30)]]))?.frameRate).toBe(30);
  });
});

describe('rejects what is not a KTX2', () => {
  const junk = new Uint8Array(256).fill(7);
  it('extractKTX2Metadata', () => expect(extractKTX2Metadata(junk)).toBeNull());
});

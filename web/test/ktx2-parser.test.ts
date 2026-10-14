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

describe('rejects what is not a KTX2', () => {
  const junk = new Uint8Array(256).fill(7);
  it('extractKTX2Metadata', () => expect(extractKTX2Metadata(junk)).toBeNull());
});

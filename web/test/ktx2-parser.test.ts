import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { describe, expect, it } from 'vitest';
import {
  ASTC_FORMAT_MAP,
  extractKTX2Metadata,
  getASTCBlockSize,
  getASTCBytesPerLayer,
  isGLBContainer,
  parseGLBContainer,
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
  glb?: boolean;
  parts?: { frames: number; columns: number; rows: number }[];
  metadata: Record<string, unknown>;
}

const expected: Record<string, Expected> = JSON.parse(fs.readFileSync(path.join(fixturesDir, 'expected.json'), 'utf8'));
delete expected._comment;

/** The file as the player sees it: an outer .zst wrapper is removed first. */
function load(name: string): Uint8Array {
  const data = fs.readFileSync(path.join(fixturesDir, name));
  return new Uint8Array(name.endsWith('.zst') ? zlib.zstdDecompressSync(data) : data);
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

describe.each(Object.entries(expected).filter(([, e]) => !e.glb))('%s', (name, e) => {
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

describe.each(Object.entries(expected).filter(([, e]) => e.glb))('%s', (name, e) => {
  const data = load(name);

  it('parses into one KTX2 per part', () => {
    expect(isGLBContainer(data)).toBe(true);
    const glb = parseGLBContainer(data);
    expect(glb).not.toBeNull();
    expect(glb!.metadata).toMatchObject(e.metadata);
    expect(glb!.ktx2Parts).toHaveLength(e.parts!.length);
    glb!.ktx2Parts.forEach((part, i) => {
      expect(Array.from(part.subarray(0, 4)), `part ${i} magic`).toEqual([0xab, 0x4b, 0x54, 0x58]);
      const h = header(part);
      const { columns, rows } = e.parts![i];
      expect({ width: h.width, height: h.height }, `part ${i} size`).toEqual({
        width: columns * (e.metadata.frameWidth as number),
        height: rows * (e.metadata.frameHeight as number),
      });
    });
    expect(glb!.metadata.parts?.map((p) => ({ columns: p.columns, rows: p.rows }))).toEqual(
      e.parts!.map(({ columns, rows }) => ({ columns, rows })),
    );
  });
});

describe('rejects what is not a KTX2 or GLB', () => {
  const junk = new Uint8Array(256).fill(7);
  it('extractKTX2Metadata', () => expect(extractKTX2Metadata(junk)).toBeNull());
  it('parseGLBContainer', () => expect(parseGLBContainer(junk)).toBeNull());
  it('isGLBContainer on a KTX2', () => expect(isGLBContainer(load('card_383x217/astc4x4.ktx2'))).toBe(false));
});

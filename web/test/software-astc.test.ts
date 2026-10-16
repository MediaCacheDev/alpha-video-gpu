import fs from 'node:fs';
import path from 'node:path';
import { beforeAll, describe, expect, it } from 'vitest';
import { getASTCBlockSize, getASTCBytesPerLayer } from '../src/ktx2-parser';
import { loadSoftwareAstcDecoder, type SoftwareAstcDecoder } from '../src/software-astc';
import { ZSTDDecoder } from '../src/zstddec-stream-bundled.js';

const fixturesDir = path.resolve(__dirname, '../../tests/fixtures');

interface Rect { x: number; y: number; w: number; h: number }
interface Layout {
  codeCells: Rect[];
  invertedCells: Rect[];
  alphaCodeCells: Rect[];
  alphaRamp: { name: string; rect: Rect; rgba: [number, number, number, number] }[];
}

const expected: Record<string, unknown> = JSON.parse(fs.readFileSync(path.join(fixturesDir, 'expected.json'), 'utf8'));
const fixtures = Object.keys(expected).filter((name) => name.endsWith('.ktx2'));

/** Every layer of a fixture as ASTC blocks, zstd-decompressed if need be. */
async function astcLayers(name: string) {
  const data = new Uint8Array(fs.readFileSync(path.join(fixturesDir, name)));
  const view = new DataView(data.buffer, data.byteOffset, data.byteLength);
  const vkFormat = view.getUint32(12, true), width = view.getUint32(20, true), height = view.getUint32(24, true);
  const layers = view.getUint32(32, true);
  const offset = Number(view.getBigUint64(80, true)), length = Number(view.getBigUint64(88, true));
  let level0 = data.subarray(offset, offset + length);
  if (view.getUint32(44, true) === 2) {
    const zstd = new ZSTDDecoder();
    await zstd.init();
    level0 = zstd.decode(level0, Number(view.getBigUint64(96, true)));
  }
  const bytesPerLayer = getASTCBytesPerLayer(width, height, vkFormat);
  const layer = (i: number) => level0.subarray(i * bytesPerLayer, (i + 1) * bytesPerLayer);
  return { vkFormat, width, height, layers, layer };
}

/** Mean of one RGBA channel over the middle of a rect, away from block edges. */
function mean(rgba: Uint8Array, width: number, r: Rect, channel: number): number {
  const inset = Math.floor(r.w / 4);
  let sum = 0, n = 0;
  for (let y = r.y + inset; y < r.y + r.h - inset; y++) {
    for (let x = r.x + inset; x < r.x + r.w - inset; x++) {
      sum += rgba[(y * width + x) * 4 + channel];
      n++;
    }
  }
  return sum / n;
}

function readCode(rgba: Uint8Array, width: number, cells: Rect[], channel: number, inverted = false): number {
  return cells.reduce((code, cell) => (code << 1) | (mean(rgba, width, cell, channel) > 127 !== inverted ? 1 : 0), 0);
}

let decoder: SoftwareAstcDecoder;
beforeAll(async () => { decoder = await loadSoftwareAstcDecoder(); });

describe.each(fixtures)('software ASTC decoding of %s', (name) => {
  const layout: Layout = JSON.parse(fs.readFileSync(path.join(fixturesDir, path.dirname(name), 'layout.json'), 'utf8'));

  it('reads back every frame index, in colour and in alpha', async () => {
    const { vkFormat, width, height, layers, layer } = await astcLayers(name);
    const { blockWidth, blockHeight } = getASTCBlockSize(vkFormat);
    for (let i = 0; i < layers; i++) {
      const rgba = decoder.decode(layer(i), width, height, blockWidth, blockHeight);
      expect(rgba.length).toBe(width * height * 4);
      expect(readCode(rgba, width, layout.codeCells, 0), `frame ${i}`).toBe(i);
      expect(readCode(rgba, width, layout.invertedCells, 0, true), `frame ${i} inverted`).toBe(i);
      expect(readCode(rgba, width, layout.alphaCodeCells, 3), `frame ${i} alpha`).toBe(i);
    }
  });

  it('keeps the alpha ramp', async () => {
    const { vkFormat, width, height, layer } = await astcLayers(name);
    const { blockWidth, blockHeight } = getASTCBlockSize(vkFormat);
    const rgba = decoder.decode(layer(0), width, height, blockWidth, blockHeight);
    for (const patch of layout.alphaRamp) {
      expect(Math.abs(mean(rgba, width, patch.rect, 3) - patch.rgba[3]), patch.name).toBeLessThan(8);
    }
  });
});

/**
 * KTX2 Parsing Utilities
 *
 * Functions for extracting KTX2 metadata and mapping ASTC formats.
 */

import type { Metadata } from './types';

/**
 * Extract BasisPlayerMetadata from KTX2 key-value data section.
 * Returns null if not found.
 */
export function extractKTX2Metadata(ktx2Data: Uint8Array): Metadata | null {
  try {
    const view = new DataView(
      ktx2Data.buffer,
      ktx2Data.byteOffset,
      ktx2Data.byteLength
    );

    // Check KTX2 magic: 0xAB 0x4B 0x54 0x58
    if (
      ktx2Data[0] !== 0xab ||
      ktx2Data[1] !== 0x4b ||
      ktx2Data[2] !== 0x54 ||
      ktx2Data[3] !== 0x58
    ) {
      return null;
    }

    const kvdByteOffset = view.getUint32(56, true);
    const kvdByteLength = view.getUint32(60, true);
    if (kvdByteLength === 0) return null;

    let pos = kvdByteOffset;
    const kvdEnd = kvdByteOffset + kvdByteLength;

    while (pos < kvdEnd) {
      const entryLength = view.getUint32(pos, true);
      pos += 4;
      if (entryLength === 0) break;

      // Read key (null-terminated)
      let keyEnd = pos;
      while (keyEnd < pos + entryLength && ktx2Data[keyEnd] !== 0) keyEnd++;
      const key = new TextDecoder().decode(ktx2Data.slice(pos, keyEnd));

      // Read value (strip trailing nulls)
      const valueStart = keyEnd + 1;
      const valueEnd = pos + entryLength;
      let actualValueEnd = valueEnd;
      while (actualValueEnd > valueStart && ktx2Data[actualValueEnd - 1] === 0) {
        actualValueEnd--;
      }

      if (key === 'BasisPlayerMetadata') {
        const valueStr = new TextDecoder().decode(
          ktx2Data.slice(valueStart, actualValueEnd)
        );
        return JSON.parse(valueStr);
      }

      pos += entryLength;
      // Align to 4 bytes
      pos += (4 - (entryLength % 4)) % 4;
    }

    return null;
  } catch (error) {
    console.warn('[AlphaVideoGpu] Failed to extract KTX2 metadata:', error);
    return null;
  }
}

/**
 * ASTC VkFormat → WebGL compressed format mapping.
 * Covers all 14 ASTC block sizes (UNORM and SRGB variants).
 */
export const ASTC_FORMAT_MAP: Record<number, number> = {
  // 4x4
  157: 0x93b0, 158: 0x93d0,
  // 5x4
  159: 0x93b1, 160: 0x93d1,
  // 5x5
  161: 0x93b2, 162: 0x93d2,
  // 6x5
  163: 0x93b3, 164: 0x93d3,
  // 6x6
  165: 0x93b4, 166: 0x93d4,
  // 8x5
  167: 0x93b5, 168: 0x93d5,
  // 8x6
  169: 0x93b6, 170: 0x93d6,
  // 8x8
  171: 0x93b7, 172: 0x93d7,
  // 10x5
  173: 0x93b8, 174: 0x93d8,
  // 10x6
  175: 0x93b9, 176: 0x93d9,
  // 10x8
  177: 0x93ba, 178: 0x93da,
  // 10x10
  179: 0x93bb, 180: 0x93db,
  // 12x10
  181: 0x93bc, 182: 0x93dc,
  // 12x12
  183: 0x93bd, 184: 0x93dd,
};

/**
 * ASTC VkFormat → block dimensions (width, height).
 * All ASTC blocks are 128 bits (16 bytes), but pixel coverage varies.
 * VkFormat values come in UNORM/SRGB pairs; both map to the same block size.
 */
export function getASTCBlockSize(vkFormat: number): { blockWidth: number; blockHeight: number } {
  // VkFormat 157–184: ASTC UNORM/SRGB pairs
  // Each pair shares the same block size. Normalize to the base (UNORM) value.
  const base = (vkFormat % 2 === 0) ? vkFormat - 1 : vkFormat;
  switch (base) {
    case 157: return { blockWidth: 4,  blockHeight: 4 };
    case 159: return { blockWidth: 5,  blockHeight: 4 };
    case 161: return { blockWidth: 5,  blockHeight: 5 };
    case 163: return { blockWidth: 6,  blockHeight: 5 };
    case 165: return { blockWidth: 6,  blockHeight: 6 };
    case 167: return { blockWidth: 8,  blockHeight: 5 };
    case 169: return { blockWidth: 8,  blockHeight: 6 };
    case 171: return { blockWidth: 8,  blockHeight: 8 };
    case 173: return { blockWidth: 10, blockHeight: 5 };
    case 175: return { blockWidth: 10, blockHeight: 6 };
    case 177: return { blockWidth: 10, blockHeight: 8 };
    case 179: return { blockWidth: 10, blockHeight: 10 };
    case 181: return { blockWidth: 12, blockHeight: 10 };
    case 183: return { blockWidth: 12, blockHeight: 12 };
    default:  return { blockWidth: 4,  blockHeight: 4 }; // fallback
  }
}

/**
 * Calculate bytes per layer for an ASTC-compressed texture.
 * All ASTC blocks are 16 bytes regardless of block dimensions.
 */
export function getASTCBytesPerLayer(
  width: number, height: number, vkFormat: number
): number {
  const { blockWidth, blockHeight } = getASTCBlockSize(vkFormat);
  const blocksX = Math.ceil(width / blockWidth);
  const blocksY = Math.ceil(height / blockHeight);
  return blocksX * blocksY * 16;
}

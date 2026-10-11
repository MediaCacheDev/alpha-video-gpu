/**
 * KTX2 Parsing Utilities
 *
 * Functions for parsing KTX2 files, GLB containers, extracting metadata,
 * and mapping texture formats.
 */

import type { Metadata, GLBParseResult } from './types';

/**
 * Check if data is a GLB container (magic: "glTF")
 */
export function isGLBContainer(data: Uint8Array): boolean {
  return (
    data[0] === 0x67 &&
    data[1] === 0x6C &&
    data[2] === 0x54 &&
    data[3] === 0x46
  );
}

/**
 * Parse GLB container and extract KTX2 parts.
 * GLB format: 12-byte header + JSON chunk + BIN chunk
 */
export function parseGLBContainer(data: Uint8Array): GLBParseResult | null {
  try {
    const view = new DataView(data.buffer, data.byteOffset, data.byteLength);
    if (!isGLBContainer(data)) return null;

    const version = view.getUint32(4, true);
    if (version !== 2) {
      console.warn('[AlphaVideoGpu] Unsupported GLB version:', version);
      return null;
    }

    let offset = 12;

    // JSON chunk
    const jsonChunkLength = view.getUint32(offset, true);
    offset += 4;
    const jsonChunkType = view.getUint32(offset, true);
    offset += 4;

    if (jsonChunkType !== 0x4e4f534a) {
      // 'JSON' in little-endian
      console.warn(
        '[AlphaVideoGpu] Expected JSON chunk, got:',
        jsonChunkType.toString(16)
      );
      return null;
    }

    const jsonData = new TextDecoder().decode(
      data.slice(offset, offset + jsonChunkLength)
    );
    const metadata = JSON.parse(jsonData.trim()) as Metadata;
    offset += jsonChunkLength;

    // BIN chunk
    const binChunkLength = view.getUint32(offset, true);
    offset += 4;
    const binChunkType = view.getUint32(offset, true);
    offset += 4;

    if (binChunkType !== 0x004e4942) {
      // 'BIN\0' in little-endian
      console.warn(
        '[AlphaVideoGpu] Expected BIN chunk, got:',
        binChunkType.toString(16)
      );
      return null;
    }

    const binData = data.slice(offset, offset + binChunkLength);
    const ktx2Parts: Uint8Array[] = [];
    const partsInfo = (metadata as any).ktx2Parts || [];

    for (const partInfo of partsInfo) {
      ktx2Parts.push(
        binData.slice(partInfo.byteOffset, partInfo.byteOffset + partInfo.byteLength)
      );
    }

    return { metadata, ktx2Parts };
  } catch (error) {
    console.warn('[AlphaVideoGpu] Failed to parse GLB:', error);
    return null;
  }
}

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

/**
 * Detect the best GPU-compressed texture format supported by the browser.
 * Returns a Basis Universal target format constant.
 */
export function detectTextureFormat(
  gl: WebGLRenderingContext,
  BasisModule: any
): number {
  const formats = [
    { ext: 'WEBGL_compressed_texture_astc', format: BasisModule.cTFASTC_4x4_RGBA },
    { ext: 'WEBGL_compressed_texture_s3tc', format: BasisModule.cTFBC3_RGBA },
    { ext: 'WEBGL_compressed_texture_etc', format: BasisModule.cTFETC2_RGBA },
    { ext: 'WEBGL_compressed_texture_pvrtc', format: BasisModule.cTFPVRTC1_4_RGBA },
  ];
  for (const { ext, format } of formats) {
    if (gl.getExtension(ext)) return format;
  }
  return BasisModule.cTFRGBA32;
}

/**
 * Map a Basis target format to the corresponding WebGL compressed format constant.
 */
export function getGLCompressedFormat(
  gl: WebGLRenderingContext,
  _basisFormat: number,
  _hasAlpha: boolean
): number {
  const COMPRESSED_RGBA_ASTC_4x4_KHR = 0x93b0;
  const COMPRESSED_RGBA_S3TC_DXT5_EXT = 0x83f3;
  const COMPRESSED_RGBA8_ETC2_EAC = 0x9278;
  const COMPRESSED_RGBA_PVRTC_4BPPV1_IMG = 0x8c02;

  if (gl.getExtension('WEBGL_compressed_texture_astc'))
    return COMPRESSED_RGBA_ASTC_4x4_KHR;
  if (gl.getExtension('WEBGL_compressed_texture_s3tc'))
    return COMPRESSED_RGBA_S3TC_DXT5_EXT;
  if (gl.getExtension('WEBGL_compressed_texture_etc'))
    return COMPRESSED_RGBA8_ETC2_EAC;
  if (gl.getExtension('WEBGL_compressed_texture_pvrtc'))
    return COMPRESSED_RGBA_PVRTC_4BPPV1_IMG;

  return gl.RGBA;
}

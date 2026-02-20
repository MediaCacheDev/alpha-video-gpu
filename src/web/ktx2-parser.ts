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
      console.warn('[KTX2Core] Unsupported GLB version:', version);
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
        '[KTX2Core] Expected JSON chunk, got:',
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
        '[KTX2Core] Expected BIN chunk, got:',
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

    console.log(`[KTX2Core] Parsed GLB container: ${ktx2Parts.length} KTX2 parts`);
    return { metadata, ktx2Parts };
  } catch (error) {
    console.warn('[KTX2Core] Failed to parse GLB:', error);
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
    console.warn('[KTX2Core] Failed to extract KTX2 metadata:', error);
    return null;
  }
}

/**
 * ASTC VkFormat → WebGL compressed format mapping.
 */
export const ASTC_FORMAT_MAP: Record<number, number> = {
  157: 0x93b0, // COMPRESSED_RGBA_ASTC_4x4_KHR
  158: 0x93d0, // COMPRESSED_SRGB8_ALPHA8_ASTC_4x4_KHR
  159: 0x93b1, 160: 0x93d1, 161: 0x93b2, 162: 0x93d2,
  163: 0x93b3, 164: 0x93d3, 165: 0x93b4, 166: 0x93d4,
  167: 0x93b5, 168: 0x93d5, 169: 0x93b6, 170: 0x93d6,
  171: 0x93b7, 172: 0x93d7, 173: 0x93b8, 174: 0x93d8,
  175: 0x93b9, 176: 0x93d9, 177: 0x93ba, 178: 0x93da,
  179: 0x93bb, 180: 0x93db, 181: 0x93bc, 182: 0x93dc,
  183: 0x93bd, 184: 0x93dd,
};

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

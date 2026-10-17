/**
 * Reads the seek table that texenc writes after a KTX2 level's zstd frames: zstd's seekable
 * format (contrib/seekable_format), a skippable frame listing each frame's compressed and
 * decompressed size, ending with a 9-byte footer. A level without one is a single stream, which
 * decodes only from its start.
 */

const SEEK_TABLE_MAGIC = 0x184d2a5e;
const SEEKABLE_FOOTER_MAGIC = 0x8f92eab1;
const FOOTER_SIZE = 9;
const SKIPPABLE_HEADER_SIZE = 8;
const CHECKSUM_FLAG = 0x80;
const RESERVED_BITS = 0x7c;

export interface ZstdSeekTable {
  /** Where each zstd frame starts in the level data, ascending from 0. */
  compressedOffsets: number[];
  /** Where each frame's output starts in the decompressed level, ascending from 0. */
  decompressedOffsets: number[];
}

/** A place the decoder can start: a frame's start, compressed and decompressed. */
export interface SeekPoint {
  compressedOffset: number;
  decompressedOffset: number;
}

/**
 * The seek table at the end of the level data, or null when there is none or it doesn't
 * describe exactly the frames before it (then the level plays as one stream).
 *
 * @param level the level data, or its last bytes holding the table
 * @param tailOffset where those bytes start in the level, when they are only its tail
 */
export function parseZstdSeekTable(level: Uint8Array, decompressedLength?: number, tailOffset = 0): ZstdSeekTable | null {
  if (level.length < SKIPPABLE_HEADER_SIZE + FOOTER_SIZE) return null;
  const view = new DataView(level.buffer, level.byteOffset, level.byteLength);
  const end = level.length;
  if (view.getUint32(end - 4, true) !== SEEKABLE_FOOTER_MAGIC) return null;
  const descriptor = level[end - 5];
  if (descriptor & RESERVED_BITS) return null;
  const frameCount = view.getUint32(end - FOOTER_SIZE, true);
  const entrySize = descriptor & CHECKSUM_FLAG ? 12 : 8;

  const tableSize = frameCount * entrySize + FOOTER_SIZE;
  const tableStart = end - SKIPPABLE_HEADER_SIZE - tableSize;
  if (frameCount === 0 || tableStart < 0) return null;
  if (view.getUint32(tableStart, true) !== SEEK_TABLE_MAGIC) return null;
  if (view.getUint32(tableStart + 4, true) !== tableSize) return null;

  const compressedOffsets: number[] = [];
  const decompressedOffsets: number[] = [];
  let compressed = 0;
  let decompressed = 0;
  for (let i = 0; i < frameCount; i++) {
    const entry = tableStart + SKIPPABLE_HEADER_SIZE + i * entrySize;
    compressedOffsets.push(compressed);
    decompressedOffsets.push(decompressed);
    compressed += view.getUint32(entry, true);
    decompressed += view.getUint32(entry + 4, true);
  }
  if (compressed !== tailOffset + tableStart) return null;
  if (decompressedLength !== undefined && decompressed !== decompressedLength) return null;
  return { compressedOffsets, decompressedOffsets };
}

/** Reads the seek table from a level's last bytes: the footer, then the table it sizes. */
export async function readZstdSeekTable(
  read: (offset: number, length: number) => Promise<Uint8Array>,
  levelLength: number,
  decompressedLength?: number,
): Promise<ZstdSeekTable | null> {
  if (levelLength < SKIPPABLE_HEADER_SIZE + FOOTER_SIZE) return null;
  const footer = await read(levelLength - FOOTER_SIZE, FOOTER_SIZE);
  if (footer.length < FOOTER_SIZE) return null;
  const view = new DataView(footer.buffer, footer.byteOffset, footer.byteLength);
  if (view.getUint32(5, true) !== SEEKABLE_FOOTER_MAGIC || footer[4] & RESERVED_BITS) return null;
  const entrySize = footer[4] & CHECKSUM_FLAG ? 12 : 8;
  const tableStart = levelLength - SKIPPABLE_HEADER_SIZE - (view.getUint32(0, true) * entrySize + FOOTER_SIZE);
  if (tableStart < 0) return null;
  return parseZstdSeekTable(await read(tableStart, levelLength - tableStart), decompressedLength, tableStart);
}

/** The last frame starting at or before that decompressed offset; the level start without a table. */
export function seekPointBefore(table: ZstdSeekTable | null, decompressedOffset: number): SeekPoint {
  if (!table) return { compressedOffset: 0, decompressedOffset: 0 };
  const starts = table.decompressedOffsets;
  let low = 0;
  let high = starts.length - 1;
  while (low < high) {
    const mid = (low + high + 1) >> 1;
    if (starts[mid] <= decompressedOffset) low = mid;
    else high = mid - 1;
  }
  return { compressedOffset: table.compressedOffsets[low], decompressedOffset: starts[low] };
}

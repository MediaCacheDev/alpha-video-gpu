import {
  blobSource,
  createStore,
  httpSource,
  isByteSource,
  openReader,
  type ByteSource,
} from 'range-source-web';
import type { KTX2Source, KTX2SourceOptions, KTX2Storage } from './types';

/** Keeps this player's stored clips apart from other players' (each has its own cap). */
export const STORAGE_NAMESPACE = 'alpha-video-gpu';

/** A source opened for reading by offset. */
export interface SourceReader {
  readonly size: number;
  read(offset: number, length: number, signal?: AbortSignal): Promise<Uint8Array>;
  close(): void;
}

export interface OpenOptions extends KTX2SourceOptions {
  storage?: KTX2Storage;
  maxStorageBytes?: number;
  signal?: AbortSignal;
}

/** asset://path names the app's bundled media; on the web it is served from the site root. */
export function resolveWebUri(uri: string): string {
  return uri.startsWith('asset://') ? '/' + uri.slice('asset://'.length).replace(/^\/+/, '') : uri;
}

/** A name for the source, to tell whether setSource was given the same one again. */
export function sourceIdentity(source: KTX2Source, cacheKey?: string): unknown {
  return typeof source === 'string' ? cacheKey || source : source;
}

export async function openKtx2Source(source: KTX2Source, options: OpenOptions = {}): Promise<SourceReader> {
  if (source instanceof ArrayBuffer || source instanceof Uint8Array) {
    return memoryReader(source instanceof Uint8Array ? source : new Uint8Array(source));
  }

  let byteSource: ByteSource;
  let key: string | undefined;
  let storage: KTX2Storage;
  if (typeof source === 'string') {
    const uri = resolveWebUri(source);
    byteSource = httpSource(uri, { headers: options.headers });
    key = options.cacheKey || uri;
    storage = options.storage ?? 'auto';
  } else if (typeof Blob !== 'undefined' && source instanceof Blob) {
    byteSource = blobSource(source);
    key = options.cacheKey;
    storage = options.storage ?? 'none';
  } else if (isByteSource(source)) {
    byteSource = source;
    key = options.cacheKey;
    storage = options.storage ?? 'none';
  } else {
    throw new TypeError('setSource takes a URL, a Blob, an ArrayBuffer, a Uint8Array or a ByteSource');
  }

  const store = key ? createStore(storage, { namespace: STORAGE_NAMESPACE, maxBytes: options.maxStorageBytes }) : null;
  return openReader(byteSource, { store, key, signal: options.signal });
}

function memoryReader(data: Uint8Array): SourceReader {
  return {
    size: data.byteLength,
    async read(offset, length) {
      return data.subarray(offset, Math.min(offset + length, data.byteLength));
    },
    close() {},
  };
}

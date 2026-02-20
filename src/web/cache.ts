/**
 * IndexedDB File Cache
 *
 * Caches downloaded KTX2 files in IndexedDB for faster subsequent loads.
 */

const KTX2_CACHE_DB_NAME = 'ktx2-player-cache';
const KTX2_CACHE_STORE_NAME = 'files';
const KTX2_CACHE_VERSION = 1;

let cacheDbPromise: Promise<IDBDatabase> | null = null;

function openCacheDb(): Promise<IDBDatabase> {
  if (cacheDbPromise) return cacheDbPromise;

  cacheDbPromise = new Promise((resolve, reject) => {
    const request = indexedDB.open(KTX2_CACHE_DB_NAME, KTX2_CACHE_VERSION);
    request.onerror = () => {
      console.warn('[KTX2Core] IndexedDB cache unavailable:', request.error);
      reject(request.error);
    };
    request.onsuccess = () => resolve(request.result);
    request.onupgradeneeded = (event) => {
      const db = (event.target as IDBOpenDBRequest).result;
      if (!db.objectStoreNames.contains(KTX2_CACHE_STORE_NAME)) {
        db.createObjectStore(KTX2_CACHE_STORE_NAME, { keyPath: 'url' });
      }
    };
  });

  return cacheDbPromise;
}

/**
 * Retrieve a cached file by URL. Returns null on miss or error.
 */
export async function getCachedFile(url: string): Promise<ArrayBuffer | null> {
  try {
    const db = await openCacheDb();
    return new Promise((resolve) => {
      const tx = db.transaction(KTX2_CACHE_STORE_NAME, 'readonly');
      const store = tx.objectStore(KTX2_CACHE_STORE_NAME);
      const request = store.get(url);
      request.onsuccess = () => {
        if (request.result) {
          console.log(`[KTX2Core] Cache hit: ${url}`);
          resolve(request.result.data);
        } else {
          resolve(null);
        }
      };
      request.onerror = () => resolve(null);
    });
  } catch {
    return null;
  }
}

/**
 * Store a file in the cache. Errors are silently ignored.
 */
export async function setCachedFile(
  url: string,
  data: ArrayBuffer
): Promise<void> {
  try {
    const db = await openCacheDb();
    return new Promise((resolve) => {
      const tx = db.transaction(KTX2_CACHE_STORE_NAME, 'readwrite');
      const store = tx.objectStore(KTX2_CACHE_STORE_NAME);
      store.put({ url, data, timestamp: Date.now() });
      tx.oncomplete = () => {
        console.log(
          `[KTX2Core] Cached: ${url} (${(data.byteLength / 1024 / 1024).toFixed(1)}MB)`
        );
        resolve();
      };
      tx.onerror = () => resolve();
    });
  } catch {
    // Ignore cache write errors
  }
}

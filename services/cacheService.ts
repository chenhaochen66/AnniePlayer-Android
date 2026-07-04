import AsyncStorage from '@react-native-async-storage/async-storage';

const CACHE_PREFIX = 'api_cache:';
const CACHE_SIZE_KEY = 'api_cache_size';

interface CacheEntry {
  data: any;
  expiry: number;
}

async function getCacheSize(): Promise<number> {
  const size = await AsyncStorage.getItem(CACHE_SIZE_KEY);
  return size ? parseInt(size, 10) : 0;
}

async function setCacheSize(size: number) {
  await AsyncStorage.setItem(CACHE_SIZE_KEY, size.toString());
}

async function addToSize(delta: number) {
  const current = await getCacheSize();
  await setCacheSize(current + delta);
}

export const cacheService = {
  async get<T>(key: string): Promise<T | null> {
    try {
      const raw = await AsyncStorage.getItem(CACHE_PREFIX + key);
      if (!raw) return null;
      const entry: CacheEntry = JSON.parse(raw);
      if (Date.now() > entry.expiry) {
        await AsyncStorage.removeItem(CACHE_PREFIX + key);
        await addToSize(-raw.length);
        return null;
      }
      return entry.data as T;
    } catch {
      return null;
    }
  },
  async set(key: string, data: any, ttlMs: number): Promise<void> {
    try {
      const entry: CacheEntry = { data, expiry: Date.now() + ttlMs };
      const raw = JSON.stringify(entry);
      const existing = await AsyncStorage.getItem(CACHE_PREFIX + key);
      await AsyncStorage.setItem(CACHE_PREFIX + key, raw);
      await addToSize(raw.length - (existing ? existing.length : 0));
    } catch (e) {
      console.warn('[cache] set failed:', e);
    }
  },
  async clear(): Promise<void> {
    try {
      const keys = await AsyncStorage.getAllKeys();
      const cacheKeys = keys.filter((k) => k.startsWith(CACHE_PREFIX));
      if (cacheKeys.length > 0) await AsyncStorage.multiRemove(cacheKeys);
      await setCacheSize(0);
    } catch (e) {
      console.warn('[cache] clear failed:', e);
    }
  },
  async getSize(): Promise<number> {
    return getCacheSize();
  },
};

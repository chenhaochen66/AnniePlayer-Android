import * as FileSystem from 'expo-file-system/legacy';
import { cacheService } from './cacheService';

const CACHE_DIR = FileSystem.cacheDirectory ? FileSystem.cacheDirectory.replace(/\/$/, '') + '/' : '';
const ASSET_CACHE_DIR = `${CACHE_DIR}assets/`;
const DOWNLOAD_CACHE_DIR = `${CACHE_DIR}downloads/`;
const CHROME_UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';

async function ensureDir() {
  const info = await FileSystem.getInfoAsync(ASSET_CACHE_DIR);
  if (!info.exists) {
    await FileSystem.makeDirectoryAsync(ASSET_CACHE_DIR, { intermediates: true });
  }
}

function normalizeCoverUrl(url: string): string {
  if (url.includes('music.126.net') && !url.includes('param=')) {
    return url + (url.includes('?') ? '&' : '?') + 'param=800y800';
  }
  return url;
}

export async function fetchAndCacheCover(url: string): Promise<string> {
  if (!url) return '';
  const dlUrl = normalizeCoverUrl(url);
  const filename = (dlUrl.split('/').pop() || `${Date.now()}.jpg`).split('?')[0];
  const filepath = `${ASSET_CACHE_DIR}${filename}`;

  const info = await FileSystem.getInfoAsync(filepath);
  if (info.exists) return filepath;

  await ensureDir();
  try {
    const download = FileSystem.createDownloadResumable(dlUrl, filepath, {
      headers: { 'User-Agent': CHROME_UA },
    });
    const result = await download.downloadAsync();
    return result?.uri || url;
  } catch {
    return url;
  }
}

export async function fetchAndCacheAudio(url: string, songId: string): Promise<string> {
  const filename = `audio_${songId.replace(/[^a-zA-Z0-9_-]/g, '_')}`;
  const ext = url.includes('.mp3') ? '.mp3' : '.mp4';
  const filepath = `${ASSET_CACHE_DIR}${filename}${ext}`;

  const info = await FileSystem.getInfoAsync(filepath);
  if (info.exists) return filepath;

  await ensureDir();
  const download = FileSystem.createDownloadResumable(url, filepath, {
    headers: { 'User-Agent': CHROME_UA },
  });
  const result = await download.downloadAsync();
  if (!result?.uri) throw new Error('Download failed');
  return result.uri;
}

async function dirSize(dir: string): Promise<number> {
  try {
    const info = await FileSystem.getInfoAsync(dir);
    if (!info.exists) return 0;
    const names = await FileSystem.readDirectoryAsync(dir);
    let total = 0;
    for (const name of names) {
      const finfo = await FileSystem.getInfoAsync(dir + name);
      if (finfo.exists) total += (finfo as any).size || 0;
    }
    return total;
  } catch { return 0; }
}

async function totalDirSize(dir: string): Promise<number> {
  try {
    const info = await FileSystem.getInfoAsync(dir);
    if (!info.exists) return 0;
    const names = await FileSystem.readDirectoryAsync(dir);
    let total = 0;
    for (const name of names) {
      const path = dir.endsWith('/') ? dir + name : dir + '/' + name;
      const finfo = await FileSystem.getInfoAsync(path);
      if (!finfo.exists) continue;
      if ((finfo as any).isDirectory) {
        total += await totalDirSize(path);
      } else {
        total += (finfo as any).size || 0;
      }
    }
    return total;
  } catch { return 0; }
}

export async function getFileCacheSize(): Promise<number> {
  const [fileSize, apiSize] = await Promise.all([
    totalDirSize(CACHE_DIR),
    cacheService.getSize(),
  ]);
  return fileSize + apiSize;
}

export async function clearAllCache(): Promise<void> {
  await Promise.all([
    FileSystem.deleteAsync(CACHE_DIR, { idempotent: true }).catch(() => {}),
    cacheService.clear(),
  ]);
}

export async function clearAssetCache(): Promise<void> {
  const info = await FileSystem.getInfoAsync(ASSET_CACHE_DIR);
  if (info.exists) {
    await FileSystem.deleteAsync(ASSET_CACHE_DIR, { idempotent: true });
  }
}

import * as FileSystem from 'expo-file-system/legacy';
import type { Quality } from '../types';
import { decodeHtmlEntities as decodeEntities } from '../utils/stringUtils';
import { scanFile, tagAudioFile } from '../modules/media-scanner/src/MediaScanner';
import { parseLrc } from '../utils/lrcParser';

const DBG = '[download]';
const CHROME_UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36';

interface ActiveDownload {
  resumable: FileSystem.DownloadResumable;
  tempPath: string;
}

const activeDownloads = new Map<string, ActiveDownload>();

function ext(q: Quality): string { return q === 'flac' || q === 'hires' ? '.flac' : '.mp3'; }

export function pauseDownload(songId: string): boolean {
  const ad = activeDownloads.get(songId);
  if (!ad) return false;
  ad.resumable.pauseAsync().catch(() => {});
  activeDownloads.delete(songId);
  return true;
}

export function cancelDownload(songId: string): void {
  const ad = activeDownloads.get(songId);
  if (ad) {
    ad.resumable.cancelAsync().catch(() => {});
    FileSystem.deleteAsync(ad.tempPath, { idempotent: true }).catch(() => {});
    activeDownloads.delete(songId);
  }
}

function mergeLyrics(lrc: string, tlyric?: string): string {
  if (!tlyric) return lrc;
  const lrcLines = parseLrc(lrc);
  const tlyricLines = parseLrc(tlyric);
  const transMap = new Map<number, string>();
  for (const tl of tlyricLines) {
    transMap.set(Math.round(tl.time * 1000), tl.text);
  }
  const result: string[] = [];
  for (const line of lrcLines) {
    const timeKey = Math.round(line.time * 1000);
    const min = Math.floor(line.time / 60);
    const sec = Math.floor(line.time % 60);
    const frac = Math.round((line.time - Math.floor(line.time)) * 100);
    const ts = `[${String(min).padStart(2, '0')}:${String(sec).padStart(2, '0')}.${String(frac).padStart(2, '0')}]`;
    result.push(ts + line.text);
    const trans = transMap.get(timeKey);
    if (trans) result.push(ts + trans);
  }
  return result.join('\n');
}

export async function downloadSong(
  songId: string, title: string, artist: string,
  coverUrl: string, album: string | undefined,
  downloadUrl: string, quality: Quality,
  onProgress: (p: number) => void, lyric: string, tlyric?: string,
): Promise<string> {
  console.log(DBG, 'start', songId, title, quality);
  title = decodeEntities(title);
  artist = decodeEntities(artist);
  const e = ext(quality);
  const safeName = (title + ' - ' + artist + e).replace(/[/\\?%*:|"<>]/g, '_');

  const tempDir = FileSystem.cacheDirectory + 'downloads/';
  await FileSystem.makeDirectoryAsync(tempDir, { intermediates: true }).catch(() => {});
  const safeSongId = songId.replace(/[^a-zA-Z0-9_-]/g, '_');
  const tempPath = tempDir + 'tmp_' + safeSongId + e;
  await FileSystem.deleteAsync(tempPath, { idempotent: true }).catch(() => {});

  console.log(DBG, 'downloading...');
  const resumable = FileSystem.createDownloadResumable(downloadUrl, tempPath,
    { headers: { 'User-Agent': CHROME_UA } },
    (p: any) => {
      if (p.totalBytesExpectedToWrite > 0) onProgress(p.totalBytesWritten / p.totalBytesExpectedToWrite);
    },
  );

  activeDownloads.set(songId, { resumable, tempPath });

  let dl;
  try {
    dl = await resumable.downloadAsync();
  } finally {
    if (activeDownloads.get(songId)?.resumable !== resumable) {
      throw new DOMException('Download cancelled', 'AbortError');
    }
  }
  if (!dl?.uri) throw new Error('Download failed');
  console.log(DBG, 'downloaded');
  onProgress(0.5);

  // Tag audio file with metadata
  let coverBase64 = '';
  try {
    const ext = (coverUrl.split('.').pop()?.split('?')[0] || 'jpg');
    const coverTmp = FileSystem.cacheDirectory + 'cover_' + songId + '.' + ext;
    await FileSystem.deleteAsync(coverTmp, { idempotent: true }).catch(() => {});
    const cr = await FileSystem.downloadAsync(coverUrl, coverTmp);
    coverBase64 = await FileSystem.readAsStringAsync(cr.uri, { encoding: FileSystem.EncodingType.Base64 });
    await FileSystem.deleteAsync(coverTmp, { idempotent: true }).catch(() => {});
  } catch { console.log(DBG, 'cover fetch failed'); }

  try {
    console.log(DBG, 'tagging audio...');
    const mergedLyric = mergeLyrics(lyric || '', tlyric);
    await tagAudioFile(tempPath.replace(/^file:\/\//, ''), title, artist, album || '', mergedLyric, coverBase64, coverUrl);
    console.log(DBG, 'tag done');
  } catch (ex: any) {
    if (activeDownloads.get(songId)?.resumable !== resumable) throw ex;
    console.log(DBG, 'tag failed:', ex.message, 'using raw');
  }
  onProgress(0.8);

  const finalPath = tempDir + safeName;
  if (tempPath !== finalPath) {
    try {
      await FileSystem.moveAsync({ from: tempPath, to: finalPath });
    } catch {
      await FileSystem.copyAsync({ from: tempPath, to: finalPath }).catch(() => {});
    }
  }

  let outUri = '';
  try { outUri = await scanFile(finalPath.replace(/^file:\/\//, '')) || ''; } catch {}

  if (outUri) {
    await FileSystem.deleteAsync(finalPath, { idempotent: true }).catch(() => {});
  }
  console.log(DBG, 'done:', outUri);
  onProgress(1);
  activeDownloads.delete(songId);
  return outUri;
}

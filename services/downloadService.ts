import * as FileSystem from 'expo-file-system/legacy';
import { File as NewFile } from 'expo-file-system';
import type { Quality } from '../types';
import { writeId3ToBytes, appendFlacMetadataToBytes, fetchImageBytes } from '../utils/id3Writer';
import { decodeHtmlEntities as decodeEntities } from '../utils/stringUtils';
import { scanFile, saveSidecar } from '../modules/media-scanner/src/MediaScanner';

const DBG = '[download]';
const CHROME_UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36';

interface ActiveDownload {
  resumable: FileSystem.DownloadResumable;
  tempPath: string;
}

const activeDownloads = new Map<string, ActiveDownload>();

function ext(q: Quality): string { return q === 'flac' || q === 'hires' ? '.flac' : '.mp3'; }

function writeFile(uri: string, data: Uint8Array): Promise<void> {
  return ((new NewFile(uri) as any).write(data));
}

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

export async function downloadSong(
  songId: string, title: string, artist: string,
  coverUrl: string, album: string | undefined,
  downloadUrl: string, quality: Quality,
  onProgress: (p: number) => void, lyric: string,
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
  const fileSize = ((await FileSystem.getInfoAsync(tempPath)) as any).size || 0;
  console.log(DBG, 'downloaded, size:', fileSize);
  onProgress(0.5);

  let outPath = tempPath;
  let sidecarCover: Uint8Array | null = null;
  try {
    if (fileSize > 100 * 1024 * 1024) {
      console.log(DBG, 'file too large, skipping metadata');
      sidecarCover = await fetchImageBytes(coverUrl);
    } else {
      const img = await fetchImageBytes(coverUrl);
      console.log(DBG, 'processing metadata...');

      if (quality === 'flac' || quality === 'hires') {
        const nf = new NewFile(tempPath);
        const audio = await nf.bytes();
        const processed = appendFlacMetadataToBytes(audio, { title, artist, album, lyric, coverUrl, imageBytes: img });
        const outTemp = tempDir + 'tagged_' + songId + e;
        await writeFile(outTemp, processed || audio);
        await FileSystem.deleteAsync(tempPath, { idempotent: true }).catch(() => {});
        outPath = outTemp;
      } else {
        const nf = new NewFile(tempPath);
        const audio = await nf.bytes();
        const processed = writeId3ToBytes(audio, { title, artist, album, lyric, coverUrl, imageBytes: img });
        const outTemp = tempDir + 'tagged_' + songId + e;
        await writeFile(outTemp, processed || audio);
        await FileSystem.deleteAsync(tempPath, { idempotent: true }).catch(() => {});
        outPath = outTemp;
      }
    }
  } catch (ex: any) {
    if (activeDownloads.get(songId)?.resumable !== resumable) throw ex;
    console.log(DBG, 'meta failed:', ex.message, 'using raw');
  }
  onProgress(0.8);

  const finalPath = tempDir + safeName;
  if (outPath !== finalPath) {
    try {
      await FileSystem.moveAsync({ from: outPath, to: finalPath });
      if (outPath !== tempPath) await FileSystem.deleteAsync(outPath, { idempotent: true }).catch(() => {});
    } catch {
      await FileSystem.copyAsync({ from: outPath, to: finalPath }).catch(() => {});
      if (outPath !== tempPath) await FileSystem.deleteAsync(outPath, { idempotent: true }).catch(() => {});
    }
    outPath = finalPath;
  }

  let outUri = '';
  try { outUri = await scanFile(outPath.replace(/^file:\/\//, '')) || ''; } catch {}

  if (outUri && fileSize > 100 * 1024 * 1024 && (sidecarCover || lyric)) {
    const baseName = safeName.replace(/\.(flac|mp3)$/, '');
    try {
      if (sidecarCover) {
        let binary = '';
        for (let i = 0; i < sidecarCover.length; i++) binary += String.fromCharCode(sidecarCover[i]);
        await saveSidecar(baseName + '.jpg', btoa(binary));
      }
      if (lyric) {
        await saveSidecar(baseName + '.lrc', btoa(unescape(encodeURIComponent(lyric))));
      }
    } catch {}
  }

  if (!outUri) {
    const docDir = FileSystem.documentDirectory;
    if (docDir) {
      outUri = docDir + encodeURIComponent(safeName);
      await FileSystem.deleteAsync(outUri, { idempotent: true }).catch(() => {});
      await FileSystem.moveAsync({ from: outPath, to: outUri }).catch(() => {});
    }
  }

  if (outPath !== tempPath) await FileSystem.deleteAsync(tempPath, { idempotent: true }).catch(() => {});
  if (outPath !== tempPath) await FileSystem.deleteAsync(outPath, { idempotent: true }).catch(() => {});
  console.log(DBG, 'done:', outUri);
  onProgress(1);
  activeDownloads.delete(songId);
  return outUri;
}

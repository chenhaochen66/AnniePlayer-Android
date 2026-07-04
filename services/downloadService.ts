import * as FileSystem from 'expo-file-system/legacy';
import { File as NewFile } from 'expo-file-system';
import type { Quality } from '../types';
import { writeId3ToBytes, appendFlacMetadataToBytes, fetchImageBytes } from '../utils/id3Writer';
import { decodeHtmlEntities as decodeEntities } from '../utils/stringUtils';
import { scanFile } from '../modules/media-scanner/src/MediaScanner';

const DBG = '[download]';
const CHROME_UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36';

function ext(q: Quality): string { return q === 'flac' || q === 'hires' ? '.flac' : '.mp3'; }

function writeFile(uri: string, data: Uint8Array): Promise<void> {
  return ((new NewFile(uri) as any).write(data));
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
  const dl = await FileSystem.createDownloadResumable(downloadUrl, tempPath,
    { headers: { 'User-Agent': CHROME_UA } },
    (p: any) => { if (p.totalBytesExpectedToWrite > 0) onProgress(p.totalBytesWritten / p.totalBytesExpectedToWrite); },
  ).downloadAsync();
  if (!dl?.uri) throw new Error('Download failed');
  const fileSize = ((await FileSystem.getInfoAsync(tempPath)) as any).size || 0;
  console.log(DBG, 'downloaded, size:', fileSize);
  onProgress(0.5);

  // Process metadata
  let outPath = tempPath;
  try {
    const img = await fetchImageBytes(coverUrl);
    console.log(DBG, 'processing metadata...');

    if (quality === 'flac' || quality === 'hires') {
      // FLAC: use manual metadata writer
      const nf = new NewFile(tempPath);
      const audio = await nf.bytes();
      const processed = appendFlacMetadataToBytes(audio, { title, artist, album, lyric, coverUrl, imageBytes: img });
      const outTemp = tempDir + 'tagged_' + songId + e;
      await writeFile(outTemp, processed || audio);
      await FileSystem.deleteAsync(tempPath, { idempotent: true }).catch(() => {});
      outPath = outTemp;
    } else {
      // MP3: use existing working code

      const nf = new NewFile(tempPath);
      const audio = await nf.bytes();
      const processed = writeId3ToBytes(audio, { title, artist, album, lyric, coverUrl, imageBytes: img });
      const outTemp = tempDir + 'tagged_' + songId + e;
      await writeFile(outTemp, processed || audio);
      await FileSystem.deleteAsync(tempPath, { idempotent: true }).catch(() => {});
      outPath = outTemp;
    }
  } catch (ex: any) {
    console.log(DBG, 'meta failed:', ex.message, 'using raw');
  }
  onProgress(0.8);

  // Rename temp file to proper name before saving
  const finalPath = tempDir + safeName;
  if (outPath !== finalPath) {
    try {
      await FileSystem.moveAsync({ from: outPath, to: finalPath });
      if (outPath !== tempPath) await FileSystem.deleteAsync(outPath, { idempotent: true }).catch(() => {});
    } catch {
      const nf = new NewFile(outPath);
      const fb = await (nf as any).bytes();
      await writeFile(finalPath, fb);
      if (outPath !== tempPath) await FileSystem.deleteAsync(outPath, { idempotent: true }).catch(() => {});
    }
    outPath = finalPath;
  }

  // Save to Music via MediaStore (visible to all players + app playback)
  let outUri = '';
  try { await scanFile(outPath.replace(/^file:\/\//, '')); } catch {}

  // Fallback: keep in document dir if MediaStore unavailable
  if (!outUri) {
    const docDir = FileSystem.documentDirectory;
    if (docDir) {
      outUri = docDir + encodeURIComponent(safeName);
      await FileSystem.deleteAsync(outUri, { idempotent: true }).catch(() => {});
      const nf = new NewFile(outPath);
      const fb = await (nf as any).bytes();
      await writeFile(outUri, fb);
    }
  }

  // Cleanup temp files
  if (outPath !== tempPath) await FileSystem.deleteAsync(tempPath, { idempotent: true }).catch(() => {});
  if (outPath !== tempPath) await FileSystem.deleteAsync(outPath, { idempotent: true }).catch(() => {});
  console.log(DBG, 'done:', outUri);
  onProgress(1);
  return outUri;
}

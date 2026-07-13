import { requireNativeModule } from 'expo-modules-core';

const MediaScanner = requireNativeModule('MediaScanner');

export function scanFile(path: string): Promise<string | null> {
  return MediaScanner.scanFile(path);
}

export function saveSidecar(filename: string, base64Data: string): Promise<void> {
  return MediaScanner.saveSidecar(filename, base64Data);
}

export function tagAudioFile(sourcePath: string, title: string, artist: string, album: string, lyric: string, coverBase64: string, coverUrl: string, albumArtist?: string, trackNumber?: string, discNumber?: string, releaseDate?: string): Promise<string> {
  const extra: Record<string, string> = {};
  if (albumArtist) extra.albumArtist = albumArtist;
  if (trackNumber) extra.trackNumber = trackNumber;
  if (discNumber) extra.discNumber = discNumber;
  if (releaseDate) extra.releaseDate = releaseDate;
  return MediaScanner.tagAudioFile(sourcePath, title, artist, album, lyric, coverBase64, coverUrl, Object.keys(extra).length > 0 ? extra : undefined);
}

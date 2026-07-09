import { requireNativeModule } from 'expo-modules-core';

const MediaScanner = requireNativeModule('MediaScanner');

export function scanFile(path: string): Promise<string | null> {
  return MediaScanner.scanFile(path);
}

export function saveSidecar(filename: string, base64Data: string): Promise<void> {
  return MediaScanner.saveSidecar(filename, base64Data);
}

export function tagAudioFile(sourcePath: string, title: string, artist: string, album: string, lyric: string, coverBase64: string, coverUrl: string): Promise<string> {
  return MediaScanner.tagAudioFile(sourcePath, title, artist, album, lyric, coverBase64, coverUrl);
}


import { requireNativeModule } from 'expo-modules-core';

const MediaScanner = requireNativeModule('MediaScanner');

export function scanFile(path: string): Promise<string | null> {
  return MediaScanner.scanFile(path);
}

export function saveSidecar(filename: string, base64Data: string): Promise<void> {
  return MediaScanner.saveSidecar(filename, base64Data);
}

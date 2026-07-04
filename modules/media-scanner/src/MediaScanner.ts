import { requireNativeModule } from 'expo-modules-core';

const MediaScanner = requireNativeModule('MediaScanner');

export function scanFile(path: string): Promise<void> {
  return MediaScanner.scanFile(path);
}

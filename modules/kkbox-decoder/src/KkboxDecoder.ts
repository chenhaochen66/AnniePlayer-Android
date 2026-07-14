import { requireNativeModule } from 'expo-modules-core';

const KkboxDecoder = requireNativeModule('KkboxDecoder');

export function decryptKkboxAudio(
  sourceUrl: string,
  licContentKey: string,
  outputPath: string,
): Promise<string> {
  return KkboxDecoder.decryptKkboxAudio(sourceUrl, licContentKey, outputPath);
}

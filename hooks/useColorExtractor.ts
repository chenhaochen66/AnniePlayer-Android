import { useCallback } from 'react';
import { Platform } from 'react-native';
import { getColors } from 'react-native-image-colors';
import { fetchAndCacheCover } from '../services/assetProxy';

const colorCache = new Map<string, string[]>();

async function extractColorsWeb(imageUrl: string): Promise<string[]> {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.crossOrigin = 'Anonymous';
    img.onload = () => {
      try {
        const canvas = document.createElement('canvas');
        const ctx = canvas.getContext('2d');
        if (!ctx) { reject(new Error('Could not get canvas context')); return; }
        const SIZE = 64;
        canvas.width = SIZE;
        canvas.height = SIZE;
        ctx.drawImage(img, 0, 0, SIZE, SIZE);
        const imageData = ctx.getImageData(0, 0, canvas.width, canvas.height);
        const data = imageData.data;
        const colorMap = new Map<string, number>();
        for (let i = 0; i < data.length; i += 16) {
          const r = data[i], g = data[i + 1], b = data[i + 2];
          const key = `${Math.round(r / 16) * 16},${Math.round(g / 16) * 16},${Math.round(b / 16) * 16}`;
          colorMap.set(key, (colorMap.get(key) || 0) + 1);
        }
        const sorted = [...colorMap.entries()]
          .sort((a, b) => b[1] - a[1])
          .slice(0, 5)
          .map(([key]) => {
            const [r, g, b] = key.split(',').map(Number);
            return `#${r.toString(16).padStart(2, '0')}${g.toString(16).padStart(2, '0')}${b.toString(16).padStart(2, '0')}`;
          });
        resolve(sorted);
      } catch { reject(new Error('Color extraction failed')); }
    };
    img.onerror = () => reject(new Error('Image load failed'));
    img.src = imageUrl;
  });
}

async function extractColorsNative(imageUrl: string): Promise<string[]> {
  const src = await fetchAndCacheCover(imageUrl);
  const result = await getColors(src, { fallback: '#6366f1', cache: true, key: imageUrl });
  if (!result) return ['#6366f1', '#8b5cf6', '#1e1b4b'];
  if (result.platform === 'ios') {
    return [result.primary || '#6366f1', result.secondary || '#8b5cf6', result.background || '#1e1b4b'];
  }
  if (result.platform === 'android') {
    return [result.average || result.dominant || '#6366f1', result.vibrant || result.muted || '#8b5cf6', result.darkMuted || result.darkVibrant || '#1e1b4b'];
  }
  return [result.dominant || '#6366f1', result.vibrant || result.muted || '#8b5cf6', result.darkMuted || result.darkVibrant || '#1e1b4b'];
}

export function useColorExtractor() {
  const isWeb = Platform.OS === 'web';

  const extractColors = useCallback(
    async (imageUrl: string): Promise<string[]> => {
      const cached = colorCache.get(imageUrl);
      if (cached) return cached;
      try {
        const colors = isWeb ? await extractColorsWeb(imageUrl) : await extractColorsNative(imageUrl);
        colorCache.set(imageUrl, colors);
        return colors;
      } catch {
        const fallback = ['#6366f1', '#8b5cf6', '#1e1b4b'];
        colorCache.set(imageUrl, fallback);
        return fallback;
      }
    },
    [isWeb],
  );

  return { extractColors };
}

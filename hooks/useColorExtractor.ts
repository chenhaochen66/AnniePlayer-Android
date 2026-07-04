import { useCallback } from 'react';
import { Platform } from 'react-native';

function getColorThiefColors(imageUrl: string): Promise<string[]> {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.crossOrigin = 'Anonymous';
    img.onload = () => {
      try {
        const canvas = document.createElement('canvas');
        const ctx = canvas.getContext('2d');
        if (!ctx) {
          reject(new Error('Could not get canvas context'));
          return;
        }
        canvas.width = img.width;
        canvas.height = img.height;
        ctx.drawImage(img, 0, 0, img.width, img.height);
        const imageData = ctx.getImageData(0, 0, canvas.width, canvas.height);
        const data = imageData.data;

        const colorMap = new Map<string, number>();
        for (let i = 0; i < data.length; i += 16) {
          const r = data[i];
          const g = data[i + 1];
          const b = data[i + 2];
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
      } catch {
        reject(new Error('Color extraction failed'));
      }
    };
    img.onerror = () => reject(new Error('Image load failed'));
    img.src = imageUrl;
  });
}

async function extractColorsNative(_imageUrl: string): Promise<string[]> {
  try {
    return ['#e74c3c', '#c0392b', '#2c3e50'];
  } catch {
    return ['#6366f1', '#8b5cf6', '#1e1b4b'];
  }
}

export function useColorExtractor() {
  const isWeb = Platform.OS === 'web';

  const extractColors = useCallback(
    async (imageUrl: string): Promise<string[]> => {
      try {
        if (isWeb) {
          return await getColorThiefColors(imageUrl);
        }
        return await extractColorsNative(imageUrl);
      } catch {
        return ['#6366f1', '#8b5cf6', '#1e1b4b'];
      }
    },
    [isWeb],
  );

  return { extractColors };
}

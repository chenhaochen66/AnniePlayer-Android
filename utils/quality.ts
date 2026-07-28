import type { Quality } from '../types';

const QUALITY_ORDER: Quality[] = ['hires', 'flac', '320k', '128k'];

export function bestQuality(desired: Quality, available: Quality[]): Quality {
  const desiredIdx = QUALITY_ORDER.indexOf(desired);
  if (desiredIdx === -1) return available[0] || '128k';

  // Try desired quality first, then fall back to lower qualities
  for (let i = desiredIdx; i < QUALITY_ORDER.length; i++) {
    if (available.includes(QUALITY_ORDER[i])) return QUALITY_ORDER[i];
  }

  // Fall back to the highest available quality
  for (const q of QUALITY_ORDER) {
    if (available.includes(q)) return q;
  }

  return '128k';
}

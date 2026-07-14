export interface LyricLine {
  time: number;
  text: string;
  translation?: string;
}

export function parseLrc(lrc: string): LyricLine[] {
  const lines: LyricLine[] = [];
  const regex = /\[(\d{2}):(\d{2})\.(\d{2,3})\](.*)/g;
  let match;
  while ((match = regex.exec(lrc)) !== null) {
    const min = parseInt(match[1], 10);
    const sec = parseInt(match[2], 10);
    const ms = parseInt(match[3], 10);
    const time = min * 60 + sec + ms / 1000;
    const text = match[4].trim();
    if (text) lines.push({ time, text });
  }
  return lines.sort((a, b) => a.time - b.time);
}

function parseTlyric(tlyric: string): Map<number, string> {
  const map = new Map<number, string>();
  const regex = /\[(\d{2}):(\d{2})\.(\d{2,3})\](.*)/g;
  let match;
  while ((match = regex.exec(tlyric)) !== null) {
    const min = parseInt(match[1], 10);
    const sec = parseInt(match[2], 10);
    const ms = parseInt(match[3], 10);
    const time = Math.round((min * 60 + sec + ms / 1000) * 1000);
    const text = match[4].trim();
    if (text) map.set(time, text);
  }
  return map;
}

export function parseLrcWithTranslation(lrc: string, tlyric?: string | null): LyricLine[] {
  const lines = parseLrc(lrc);
  if (!tlyric) return lines;
  const tmap = parseTlyric(tlyric);
  for (const line of lines) {
    const key = Math.round(line.time * 1000);
    const trans = tmap.get(key);
    if (trans) line.translation = trans;
  }
  return lines;
}

export function findCurrentLineIndex(lines: LyricLine[], currentTime: number): number {
  let lo = 0, hi = lines.length - 1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    if (lines[mid].time <= currentTime) {
      lo = mid + 1;
    } else {
      hi = mid - 1;
    }
  }
  return hi;
}

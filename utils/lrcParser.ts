export interface LyricLine {
  time: number;
  text: string;
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

export function findCurrentLineIndex(lines: LyricLine[], currentTime: number): number {
  for (let i = lines.length - 1; i >= 0; i--) {
    if (lines[i].time <= currentTime) return i;
  }
  return -1;
}

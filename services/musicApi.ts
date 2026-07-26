import type { Quality, Song } from '../types';
import { cacheService } from './cacheService';
import { fetchPlayUrl as kuwoFetchPlayUrl, fetchDownloadUrl as kuwoFetchDownloadUrl, fetchKuwoLyric } from './platforms/kuwoApi';
import { fetchNeteasePlayUrl, fetchNeteaseDownloadUrl, fetchNeteaseLyric } from './platforms/neteaseApi';

const LYRIC_CACHE_TTL = 60 * 60 * 1000;

export async function fetchPlayUrlBySong(song: Song): Promise<string> {
  if (song.platform === 'netease') return fetchNeteasePlayUrl(song.id);
  return kuwoFetchPlayUrl(song.id);
}

export async function fetchDownloadUrlBySong(song: Song, quality: Quality): Promise<string> {
  if (song.platform === 'netease') return fetchNeteaseDownloadUrl(song.id, quality);
  return kuwoFetchDownloadUrl(song.id, quality);
}

export async function fetchLyric(songId: string, title: string, artist: string): Promise<{ lrc: string; tlyric: string | null }> {
  const cacheKey = `lyric:${songId}`;
  const cached = await cacheService.get<string>(cacheKey);
  if (cached) {
    try {
      const parsed = JSON.parse(cached);
      if (parsed && typeof parsed.lrc === 'string') {
        return { lrc: parsed.lrc, tlyric: parsed.tlyric ?? null };
      }
    } catch {
    }
  }

  try {
    const result = songId.startsWith('netease:')
      ? await fetchNeteaseLyric(songId)
      : await fetchKuwoLyric(songId, title, artist);

    await cacheService.set(cacheKey, JSON.stringify({ lrc: result.lrc, tlyric: result.tlyric }), LYRIC_CACHE_TTL);
    return result;
  } catch {
    const fallback = JSON.stringify({ lrc: '[00:00.00]暂无歌词', tlyric: null });
    await cacheService.set(cacheKey, fallback, LYRIC_CACHE_TTL);
    return { lrc: '[00:00.00]暂无歌词', tlyric: null };
  }
}

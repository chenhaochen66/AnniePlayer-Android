import type { Quality, Song } from '../types';
import { cacheService } from './cacheService';
import { fetchPlayUrl as kuwoFetchPlayUrl, fetchDownloadUrl as kuwoFetchDownloadUrl, fetchKuwoLyric } from './platforms/kuwoApi';
import { fetchNeteasePlayUrl, fetchNeteaseDownloadUrl, fetchNeteaseLyric } from './platforms/neteaseApi';
import { fetchQQLyric } from './platforms/qqApi';
import { fetchKugouLyric } from './platforms/kugouApi';
import { fetchAnnieMusicUrl } from './annieSource';

const LYRIC_CACHE_TTL = 60 * 60 * 1000;

export async function fetchPlayUrlBySong(song: Song): Promise<string> {
  if (song.platform === 'netease') return fetchNeteasePlayUrl(song.id);
  if (song.platform === 'qq' || song.platform === 'kugou') {
    const source = song.platform === 'qq' ? 'tx' : 'kg';
    const id = song.id.replace(/^(qq:|kugou:)/, '');
    const quality: Quality = song.qualities.includes('320k') ? '320k' : '128k';
    return fetchAnnieMusicUrl(source, id, quality);
  }
  return kuwoFetchPlayUrl(song.id);
}

export async function fetchDownloadUrlBySong(song: Song, quality: Quality): Promise<string> {
  if (song.platform === 'netease') return fetchNeteaseDownloadUrl(song.id, quality);
  if (song.platform === 'qq' || song.platform === 'kugou') {
    const source = song.platform === 'qq' ? 'tx' : 'kg';
    const id = song.id.replace(/^(qq:|kugou:)/, '');
    return fetchAnnieMusicUrl(source, id, quality);
  }
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
    let result: { lrc: string; tlyric: string | null };
    if (songId.startsWith('netease:')) {
      result = await fetchNeteaseLyric(songId);
    } else if (songId.startsWith('qq:')) {
      result = await fetchQQLyric(songId);
    } else if (songId.startsWith('kugou:')) {
      result = await fetchKugouLyric(songId, title + ' ' + artist);
    } else {
      result = await fetchKuwoLyric(songId, title, artist);
    }

    await cacheService.set(cacheKey, JSON.stringify({ lrc: result.lrc, tlyric: result.tlyric }), LYRIC_CACHE_TTL);
    return result;
  } catch {
    const fallback = JSON.stringify({ lrc: '[00:00.00]暂无歌词', tlyric: null });
    await cacheService.set(cacheKey, fallback, LYRIC_CACHE_TTL);
    return { lrc: '[00:00.00]暂无歌词', tlyric: null };
  }
}

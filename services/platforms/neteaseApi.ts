import type { Quality, Song } from '../../types';
import { cacheService } from '../cacheService';
import { retryUntilSuccess } from '../../utils/retry';
import { decodeHtmlEntities } from '../../utils/stringUtils';

const SEARCH_CACHE_TTL = 10 * 60 * 1000;
const PLAY_URL_CACHE_TTL = 30 * 60 * 1000;

const NETEASE_API_HEADERS = {
  'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
  'Referer': 'https://music.163.com/',
  'Content-Type': 'application/x-www-form-urlencoded',
  'Cookie': 'os=pc; deviceId=pyncm!',
};

const NETEASE_MUSIC_API = 'https://c.wwwweb.top/music/url';
const NETEASE_MUSIC_HEADERS = {
  'User-Agent': 'lx-music-mobile/2.0.0',
  'Content-Type': 'application/json',
};

// ── 音质 ──

export function parseNeteaseQualities(song: any): Quality[] {
  const result: Quality[] = [];
  if (song.hr) result.push('hires');
  if (song.sq) result.push('flac');
  if (song.h) result.push('320k');
  if (song.l || song.m) result.push('128k');
  return result.sort((a, b) => {
    const order: Quality[] = ['hires', 'flac', '320k', '128k'];
    return order.indexOf(a) - order.indexOf(b);
  });
}

// ── 搜索 ──

export async function searchNeteaseMusic(query: string): Promise<Song[]> {
  const cacheKey = `netease:search:v1:${query}`;
  const cached = await cacheService.get<Song[]>(cacheKey);
  if (cached) return cached;

  const params = new URLSearchParams({ s: query, type: '1', limit: '100', offset: '0' });

  const resp = await fetch('https://music.163.com/api/cloudsearch/pc', {
    method: 'POST',
    headers: NETEASE_API_HEADERS,
    body: params.toString(),
  });
  const data = await resp.json();
  const list = data?.result?.songs || [];

  const songs: Song[] = list.map((item: any) => ({
    id: `netease:${item.id}`,
    title: decodeHtmlEntities(item.name || '未知歌曲'),
    artist: decodeHtmlEntities((item.ar || []).map((a: any) => a.name).join(' / ')),
    cover: item.al?.picUrl || '',
    album: decodeHtmlEntities(item.al?.name || ''),
    albumId: item.al?.id ? String(item.al.id) : undefined,
    duration: Math.floor((item.dt || 0) / 1000),
    platform: 'netease',
    playUrl: '',
    lyric: undefined,
    qualities: parseNeteaseQualities(item),
    albumArtist: item.ar?.[0]?.name ? decodeHtmlEntities(item.ar[0].name) : undefined,
    trackNumber: item.no ? String(item.no) : undefined,
    discNumber: item.cd ? String(item.cd) : undefined,
    releaseDate: item.publishTime ? new Date(item.publishTime).toISOString().slice(0, 10) : undefined,
  }));

  await cacheService.set(cacheKey, songs, SEARCH_CACHE_TTL);
  return songs;
}

// ── 播放链接 ──

async function fetchNeteaseMusicUrl(songId: string, quality: string): Promise<string> {
  const rawId = parseInt(songId.replace(/^netease:/, ''), 10);
  const payload = { source: 'wy', musicId: rawId, quality };

  const resp = await fetch(NETEASE_MUSIC_API, {
    method: 'POST',
    headers: NETEASE_MUSIC_HEADERS,
    body: JSON.stringify(payload),
  });
  const data = await resp.json();
  if (data.code !== 200 || !data.url) {
    throw new Error(`网易云获取链接失败: ${data.msg || 'unknown'}`);
  }
  return data.url;
}

export async function fetchNeteasePlayUrl(songId: string): Promise<string> {
  const cacheKey = `netease:playurl:${songId.replace(/^netease:/, '')}`;
  const cached = await cacheService.get<string>(cacheKey);
  if (cached) return cached;

  const url = await retryUntilSuccess(
    () => fetchNeteaseMusicUrl(songId, '320k'),
    (u) => !!u && u.startsWith('http'),
    100, 500,
  );

  await cacheService.set(cacheKey, url, PLAY_URL_CACHE_TTL);
  return url;
}

export async function fetchNeteaseDownloadUrl(songId: string, quality: Quality): Promise<string> {
  return retryUntilSuccess(
    () => fetchNeteaseMusicUrl(songId, quality),
    (u) => !!u && u.startsWith('http'),
    100, 500,
  );
}

// ── 歌词 ──

export async function fetchNeteaseLyric(songId: string): Promise<{ lrc: string; tlyric: string | null }> {
  const rawId = songId.replace(/^netease:/, '');
  const params = new URLSearchParams({ id: rawId, lv: '1', kv: '0', tv: '1' });

  const resp = await fetch('https://interface3.music.163.com/api/song/lyric', {
    method: 'POST',
    headers: NETEASE_API_HEADERS,
    body: params.toString(),
  });
  const data = await resp.json();
  if (data.code !== 200) throw new Error('netease lyric api error');
  return { lrc: data.lrc?.lyric || '[00:00.00]暂无歌词', tlyric: data.tlyric?.lyric || null };
}

// ── 专辑详情 ──

export async function getNeteaseAlbumDetail(albumId: string): Promise<{
  name: string; artist: string; publishTime: string; company: string;
  songs: { id: string; name: string; no: number; disc: string; artist: string }[];
} | null> {
  try {
    const resp = await fetch(`https://music.163.com/api/album/${albumId}`, {
      headers: NETEASE_API_HEADERS,
    });
    if (!resp.ok) return null;
    const data = await resp.json();
    const album = data?.album;
    if (!album) return null;
    return {
      name: decodeHtmlEntities(album.name || ''),
      artist: decodeHtmlEntities(album.artist?.name || album.artists?.[0]?.name || ''),
      publishTime: album.publishTime ? new Date(album.publishTime).toISOString().slice(0, 10) : '',
      company: album.company || '',
      songs: (album.songs || []).map((s: any) => ({
        id: `netease:${s.id}`,
        name: decodeHtmlEntities(s.name || ''),
        no: s.no || 0,
        disc: s.disc || '1',
        artist: decodeHtmlEntities((s.artists || []).map((a: any) => a.name).join(' / ')),
      })),
    };
  } catch {
    return null;
  }
}

// ── 歌单 ──

export async function getNeteasePlaylist(playlistId: string): Promise<{
  name: string; creator: string; cover: string; songs: Song[];
} | null> {
  try {
    const resp = await fetch('https://music.163.com/api/v6/playlist/detail', {
      method: 'POST',
      headers: NETEASE_API_HEADERS,
      body: new URLSearchParams({ id: playlistId, n: '100000' }).toString(),
    });
    const data = await resp.json();
    if (data.code !== 200 || !data.playlist) return null;
    const pl = data.playlist;
    const trackIds = pl.trackIds || [];
    const songs: Song[] = [];

    for (let i = 0; i < trackIds.length; i += 200) {
      const chunk = trackIds.slice(i, i + 200).map((t: any) => ({ id: t.id, v: t.v || 0 }));
      const detailResp = await fetch('https://music.163.com/api/v3/song/detail', {
        method: 'POST',
        headers: NETEASE_API_HEADERS,
        body: new URLSearchParams({ c: JSON.stringify(chunk) }).toString(),
      });
      const detailData = await detailResp.json();
      const list = detailData.songs || [];
      for (const s of list) {
        const cover = (s.al?.picUrl || '') + '?param=800y800';
        const qualities: Quality[] = [];
        if (s.hr) qualities.push('hires');
        if (s.sq) qualities.push('flac');
        if (s.h) qualities.push('320k');
        if (s.l || s.m) qualities.push('128k');
        songs.push({
          id: `netease:${s.id}`,
          title: decodeHtmlEntities(s.name || ''),
          artist: decodeHtmlEntities((s.ar || []).map((a: any) => a.name).join(' / ')),
          cover,
          album: decodeHtmlEntities(s.al?.name || ''),
          albumId: s.al?.id ? String(s.al.id) : undefined,
          duration: Math.floor((s.dt || 0) / 1000),
          platform: 'netease',
          playUrl: '',
          lyric: undefined,
          tlyric: undefined,
          qualities: qualities.length > 0 ? qualities : ['128k'],
        });
      }
    }

    return {
      name: pl.name || '',
      creator: pl.creator?.nickname || '',
      cover: (pl.coverImgUrl || '') + '?param=800y800',
      songs,
    };
  } catch {
    return null;
  }
}

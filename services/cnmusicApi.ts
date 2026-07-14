import { cacheService } from './cacheService';
import type { Quality, Song } from '../types';
import { retryUntilSuccess } from '../utils/retry';
import { decodeHtmlEntities } from '../utils/stringUtils';

const SEARCH_CACHE_TTL = 10 * 60 * 1000;
const PLAY_URL_CACHE_TTL = 30 * 60 * 1000;
const LYRIC_CACHE_TTL = 60 * 60 * 1000;

const KUWO_SOURCE = 'kwplayerhd_ar_4.3.0.8_tianbao_T1A_qirui.apk';
const ANDROID_UA = 'kwplayerhd_ar_4.3.0.8_tianbao_T1A_qirui.apk';
const MOBILE_UA = 'Mozilla/5.0 (Linux; Android 14; SM-S928B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36';

const BR_MAP: Record<string, string> = {
  '128k': '128kmp3',
  '320k': '320kmp3',
  'flac': '2000kflac',
  'flac24bit': '4000kflac',
};

function buildCoverUrl(item: any): string {
  const raw = item.web_albumpic_short || item.hts_img || item.img || item.pic || item.web_artistpic_short || '';
  if (!raw) return '';
  // "120/s3s94/93/211513640.jpg" → strip leading size, preserve rest
  if (/^\d+\//.test(raw)) {
    return `http://img1.kuwo.cn/star/albumcover/500/${raw.replace(/^\d+\//, '')}`;
  }
  // Full URL "https://img4.kuwo.cn/star/albumcover/240/s4s86/95/..."
  if (raw.startsWith('http')) {
    const m = raw.match(/\/star\/albumcover\/\d+\/(.+)/);
    if (m) return `http://img1.kuwo.cn/star/albumcover/500/${m[1]}`;
  }
  return raw;
}

function parseQualities(minfo: string): Quality[] {
  const parts = minfo.split(';');
  const seen = new Set<string>();
  const result: Quality[] = [];
  for (const part of parts) {
    const kv = part.split(',');
    const level = kv.find((k) => k.startsWith('level=') || k.startsWith('level:'))?.split(/[=:]/)[1];
    if (level === 'h') { if (!seen.has('128k')) { seen.add('128k'); result.push('128k'); } }
    else if (level === 'p' || level === 's') { if (!seen.has('320k')) { seen.add('320k'); result.push('320k'); } }
    else if (level === 'ff' || level === 'zp' || level === 'zply') { if (!seen.has('flac')) { seen.add('flac'); result.push('flac'); } }
  }
  return result.sort((a, b) => {
    const order: Quality[] = ['flac', '320k', '128k'];
    return order.indexOf(a) - order.indexOf(b);
  });
}

export interface SearchResults {
  netease: any[];
  kuwo: any[];
}

export async function searchMusic(query: string): Promise<SearchResults> {
  const cacheKey = `search:v3:${query}`;
  const cached = await cacheService.get<SearchResults>(cacheKey);
  if (cached) return cached;

  const url = `http://search.kuwo.cn/r.s?cluster=0&strategy=2012&vermerge=1&stype=name&show_copyright_off=1&rn=100&all=${encodeURIComponent(query)}&encoding=utf8&rformat=json&vipver=MUSIC_8.7.7.0_BCS37&ft=music&newver=1&client=kt&_=${Date.now()}`;

  const resp = await fetch(url, {
    headers: { 'User-Agent': ANDROID_UA },
  });
  const text = await resp.text();
  const normalized = text.replace(/'/g, '"');
  let list: any[] = [];
  try {
    const json = JSON.parse(normalized);
    list = json.abslist || json.list || [];
  } catch {
    const re = /"(?:abslist|list)":\s*(\[[\s\S]*?\])\s*(?:,"|}})/;
    const match = normalized.match(re);
    if (match) {
      try { list = JSON.parse(match[1]); } catch { list = []; }
    }
  }

  interface SongResult {
    id: string;
    title: string;
    artist: string;
    cover: string;
    album: string;
    albumId?: string;
    duration: number;
    platform: 'kuwo';
    playUrl: string;
    lyric: string | undefined;
    qualities: Quality[];
    albumArtist?: string;
  }

  const songs: SongResult[] = list.map((item: any) => {
    const rawRid = item.MUSICRID || '';
    const rid = rawRid.replace('MUSIC_', '');
    const qualities = parseQualities(item.MINFO || item.N_MINFO || '');
    if (qualities.length === 0) qualities.push('128k');
    return {
      id: rid,
      title: decodeHtmlEntities(item.SONGNAME || item.NAME || '未知歌曲'),
      artist: decodeHtmlEntities(item.ARTIST || '未知歌手'),
      cover: buildCoverUrl(item),
      album: decodeHtmlEntities(item.ALBUM || ''),
      albumId: item.ALBUMID || undefined,
      duration: parseInt(item.DURATION, 10) || 0,
      platform: 'kuwo' as const,
      playUrl: '',
      lyric: undefined,
      qualities,
      albumArtist: item.AARTIST ? decodeHtmlEntities(item.AARTIST) : undefined,
    };
  });

  const result: SearchResults = { netease: [], kuwo: songs };
  await cacheService.set(cacheKey, result, SEARCH_CACHE_TTL);
  return result;
}

async function fetchKuwoMusicUrl(songId: string, br: string): Promise<string> {
  const url = `http://nmobi.kuwo.cn/mobi.s?f=web&user=0&source=${KUWO_SOURCE}&type=convert_url_with_sign&rid=${songId}&br=${br}`;

  const resp = await fetch(url, {
    headers: { 'User-Agent': MOBILE_UA, 'Accept': 'application/json' },
  });
  const text = await resp.text();
  try {
    const data = JSON.parse(text);
    if (data?.code === 200 && data?.data?.url) return data.data.url;
    if (data?.url) return data.url;
    throw new Error(data?.msg || `获取播放链接失败 (${songId})`);
  } catch {
    const trimmed = text.trim();
    if (trimmed.startsWith('http')) return trimmed;
    throw new Error(`mobi.s 返回非 URL: ${trimmed.substring(0, 100)}`);
  }
}

export async function fetchPlayUrl(songId: string): Promise<string> {
  const cacheKey = `playurl:${songId}`;
  const cached = await cacheService.get<string>(cacheKey);
  if (cached) return cached;

  const url = await retryUntilSuccess(
    () => fetchKuwoMusicUrl(songId, '128kmp3'),
    (u) => !!u && u.startsWith('http'),
    100, 500,
  );

  await cacheService.set(cacheKey, url, PLAY_URL_CACHE_TTL);
  return url;
}

export async function fetchDownloadUrl(songId: string, quality: Quality): Promise<string> {
  const br = BR_MAP[quality] || '128kmp3';
  return retryUntilSuccess(
    () => fetchKuwoMusicUrl(songId, br),
    (u) => !!u && u.startsWith('http'),
    100, 500,
  );
}

// ── 网易云 ──

const NETEASE_SEARCH_CACHE_TTL = 10 * 60 * 1000;

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

function parseNeteaseQualities(song: any): Quality[] {
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

  await cacheService.set(cacheKey, songs, NETEASE_SEARCH_CACHE_TTL);
  return songs;
}

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
    () => fetchNeteaseMusicUrl(songId, '128k'),
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

// ── 统一路由 ──

export async function fetchPlayUrlBySong(song: Song): Promise<string> {
  if (song.platform === 'netease') return fetchNeteasePlayUrl(song.id);
  return fetchPlayUrl(song.id);
}

export async function fetchDownloadUrlBySong(song: Song, quality: Quality): Promise<string> {
  if (song.platform === 'netease') return fetchNeteaseDownloadUrl(song.id, quality);
  return fetchDownloadUrl(song.id, quality);
}

async function fetchNeteaseLyric(songId: string): Promise<{ lrc: string; tlyric: string | null }> {
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

async function searchKuwoMusicId(title: string, artist: string): Promise<string> {
  const query = `${title} ${artist}`;
  const url = `http://search.kuwo.cn/r.s?cluster=0&strategy=2012&vermerge=1&stype=name&show_copyright_off=1&rn=5&all=${encodeURIComponent(query)}&encoding=utf8&rformat=json&vipver=MUSIC_8.7.7.0_BCS37&ft=music&newver=1&client=kt&_=${Date.now()}`;
  const resp = await fetch(url, { headers: { 'User-Agent': ANDROID_UA } });
  const text = await resp.text();
  const normalized = text.replace(/'/g, '"');
  let list: any[] = [];
  try {
    const json = JSON.parse(normalized);
    list = json.abslist || json.list || [];
  } catch {
    const re = /"(?:abslist|list)":\s*(\[[\s\S]*?\])\s*(?:,"|}})/;
    const match = normalized.match(re);
    if (match) try { list = JSON.parse(match[1]); } catch { list = []; }
  }
  if (list.length === 0) throw new Error('kuwo search no results');
  return (list[0].MUSICRID || '').replace('MUSIC_', '');
}

async function fetchKuwoLyric(songId: string, title: string, artist: string): Promise<{ lrc: string; tlyric: string | null }> {
  const musicId = songId.startsWith('qobuz:') || songId.startsWith('kkbox:')
    ? await searchKuwoMusicId(title, artist)
    : songId;
  const resp = await fetch(
    `https://www.kuwo.cn/openapi/v1/www/lyric/getlyric?musicId=${musicId}&httpsStatus=1&plat=web_www&from=`,
    {
      headers: {
        'User-Agent': MOBILE_UA,
        'Accept': 'application/json, text/plain, */*',
        'Referer': `https://www.kuwo.cn/play_detail/${musicId}`,
      },
    },
  );
  const data = await resp.json();
  if (!data.success) throw new Error('kuwo lyric api failed');

  const lrclist = data.data?.lrclist;
  if (!lrclist || !Array.isArray(lrclist) || lrclist.length === 0) {
    return { lrc: '[00:00.00]暂无歌词', tlyric: null };
  }

  const lrcLines: string[] = [];
  const tlyricLines: string[] = [];

  const fmtTime = (sec: string): string => {
    const s = parseFloat(sec);
    const m = Math.floor(s / 60);
    const secs = Math.floor(s % 60);
    const ms = Math.floor((s % 1) * 100);
    return `[${String(m).padStart(2, '0')}:${String(secs).padStart(2, '0')}.${String(ms).padStart(2, '0')}]`;
  };

  const hasChinese = (text: string): boolean => /[\u4e00-\u9fff]/.test(text);

  let i = 0;
  while (i < lrclist.length) {
    const text = (lrclist[i].lineLyric || '').trim();
    if (!text) { i++; continue; }

    if (i + 1 < lrclist.length) {
      const nextText = (lrclist[i + 1].lineLyric || '').trim();
      if (nextText && hasChinese(nextText) && !hasChinese(text)) {
        lrcLines.push(`${fmtTime(lrclist[i].time)}${text}`);
        tlyricLines.push(`${fmtTime(lrclist[i].time)}${nextText}`);
        i += 2;
        continue;
      }
    }

    lrcLines.push(`${fmtTime(lrclist[i].time)}${text}`);
    i++;
  }

  return {
    lrc: lrcLines.join('\n') || '[00:00.00]暂无歌词',
    tlyric: tlyricLines.length > 0 ? tlyricLines.join('\n') : null,
  };
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
      // 旧格式缓存，不返回 null，继续请求 API 获取 tlyric
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


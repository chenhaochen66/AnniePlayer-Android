import type { Quality, Song, Artist, Album, AlbumSong } from '../../types';
import { cacheService } from '../cacheService';
import { retryUntilSuccess } from '../../utils/retry';
import { decodeHtmlEntities } from '../../utils/stringUtils';

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

// ── 封面 ──

export function buildCoverUrl(item: any): string {
  const raw = item.web_albumpic_short || item.hts_img || item.img || item.pic || item.web_artistpic_short || '';
  if (!raw) return '';
  if (/^\d+\//.test(raw)) {
    return `http://img1.kuwo.cn/star/albumcover/800/${raw.replace(/^\d+\//, '')}`;
  }
  if (raw.startsWith('http')) {
    const m = raw.match(/\/star\/albumcover\/\d+\/(.+)/);
    if (m) return `http://img1.kuwo.cn/star/albumcover/800/${m[1]}`;
  }
  return raw;
}

export function buildAlbumCoverUrl(album: Album): string {
  const raw = album.pic || album.hts_img || album.img || '';
  if (!raw) return '';
  if (/^\d+\//.test(raw)) {
    return `http://img1.kuwo.cn/star/albumcover/800/${raw.replace(/^\d+\//, '')}`;
  }
  if (raw.startsWith('http')) {
    const m = raw.match(/\/star\/albumcover\/\d+\/(.+)/);
    if (m) return `http://img1.kuwo.cn/star/albumcover/800/${m[1]}`;
  }
  return raw;
}

// ── 音质 ──

export function parseQualities(minfo: string): Quality[] {
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

// ── 搜索 ──

export async function searchMusic(query: string): Promise<Song[]> {
  const cacheKey = `search:v3:${query}`;
  const cached = await cacheService.get<Song[]>(cacheKey);
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

  const songs: Song[] = list.map((item: any) => {
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
      tlyric: undefined,
      qualities,
      albumArtist: item.AARTIST ? decodeHtmlEntities(item.AARTIST) : undefined,
    };
  });

  await cacheService.set(cacheKey, songs, SEARCH_CACHE_TTL);
  return songs;
}

export async function searchAlbum(keyword: string, page = 1, pageSize = 50): Promise<Album[]> {
  const url = `http://search.kuwo.cn/r.s?ft=album&all=${encodeURIComponent(keyword)}&pn=${page}&rn=${pageSize}&rformat=json&client=kt&vipver=1&encoding=utf8&cluster=0&strategy=2012`;

  const resp = await fetch(url, { headers: { 'User-Agent': ANDROID_UA } });
  const text = await resp.text();
  const normalized = text.replace(/'/g, '"');

  let list: any[] = [];
  try {
    const json = JSON.parse(normalized);
    list = json.albumlist || json.list || [];
  } catch {
    const re = /"(?:albumlist|list)":\s*(\[[\s\S]*?\])\s*(?:,"|}})/;
    const match = normalized.match(re);
    if (match) {
      try { list = JSON.parse(match[1]); } catch { list = []; }
    }
  }

  return list.map((item: any) => ({
    albumid: item.albumid || '',
    name: decodeHtmlEntities(item.name || item.NAME || '未知专辑'),
    artist: decodeHtmlEntities(item.artist || item.ARTIST || '未知歌手'),
    musiccnt: item.musiccnt || item.MUSICCNT || '0',
    pub: item.pub || item.PUB || '',
    pic: item.pic || item.PIC || '',
    img: item.img || item.IMG || '',
    hts_img: item.hts_img || item.HTS_IMG || '',
    company: item.company || item.COMPANY || '',
    lang: item.lang || item.LANG || '',
    vip: item.vip || item.VIP || '',
    PLAYCNT: item.PLAYCNT || '0',
  }));
}

// ── 歌手 ──

const API_BASE = 'https://www.kuwo.cn/api/www';
const API_BASE_ALBUM = 'https://wapi.kuwo.cn/api/www';

function generateReqId(): string {
  const hex = '0123456789abcdef';
  let id = '';
  for (let i = 0; i < 36; i++) {
    if (i === 8 || i === 13 || i === 18 || i === 23) {
      id += '-';
    } else if (i === 14) {
      id += '1';
    } else if (i === 19) {
      id += hex[(Math.random() * 4 | 0) + 8];
    } else {
      id += hex[Math.random() * 16 | 0];
    }
  }
  return id;
}

export async function searchArtist(keyword: string): Promise<Artist[]> {
  const cacheKey = `artist:search:v1:${keyword}`;
  const cached = await cacheService.get<Artist[]>(cacheKey);
  if (cached) return cached;

  const reqId = generateReqId();
  const url = `${API_BASE}/search/searchArtistBykeyWord?key=${encodeURIComponent(keyword)}&pn=1&rn=30&httpsStatus=1&reqId=${reqId}&plat=web_www&from=`;
  try {
    const resp = await fetch(url, {
      credentials: 'omit',
      headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36',
        'Accept': 'application/json, text/plain, */*',
        'Accept-Encoding': 'gzip, deflate, br, zstd',
        'sec-ch-ua-platform': '"Android"',
        'Secret': '5420e9d5615d1142b37ce4f778c0fc5466bbbf857cb7f1333cd123c1b5de34e80565f09f',
        'sec-ch-ua': '"Android WebView";v="149", "Chromium";v="149", "Not)A;Brand";v="24"',
        'sec-ch-ua-mobile': '?1',
        'X-Requested-With': 'idm.internet.download.manager.plus',
        'Sec-Fetch-Site': 'same-origin',
        'Sec-Fetch-Mode': 'cors',
        'Sec-Fetch-Dest': 'empty',
        'Referer': `https://www.kuwo.cn/search/singers?key=${encodeURIComponent(keyword)}`,
        'Accept-Language': 'zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7',
        'Cookie': 'Hm_Iuvt_cdb524f42f23cer9b268564v7y735ewrq2324=wpbwpzyimYDf3zMyntNRKMm45QXX3XQd',
      },
    });
    if (!resp.ok) return [];
    const json = await resp.json();
    if (json.code !== 200) return [];
    const list = json.data?.artistList || json.data?.list || [];
    const artists = list.map((item: any) => ({
      id: String(item.id || ''),
      name: decodeHtmlEntities(item.name || '未知歌手'),
      picture: item.pic300 || item.pic || '',
      musicNum: item.musicNum || 0,
    }));
    await cacheService.set(cacheKey, artists, 30 * 1000);
    return artists;
  } catch {
    return [];
  }
}

export async function getArtistSongs(artistId: string, page: number = 1): Promise<{ songs: Song[]; total: number }> {
  const reqId = generateReqId();
  const url = `${API_BASE}/artist/artistMusic?artistid=${artistId}&pn=${page}&rn=20&httpsStatus=1&reqId=${reqId}&plat=web_www&from=`;
  try {
    const resp = await fetch(url, {
      credentials: 'omit',
      headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36',
        'Accept': 'application/json, text/plain, */*',
        'Accept-Encoding': 'gzip, deflate, br, zstd',
        'sec-ch-ua-platform': '"Android"',
        'Secret': '4020c1e17b54296da65df0e32a8ce97445b88ba3748ad24a6fea38adedb112e202abc8c8',
        'sec-ch-ua': '"Android WebView";v="149", "Chromium";v="149", "Not)A;Brand";v="24"',
        'sec-ch-ua-mobile': '?1',
        'X-Requested-With': 'idm.internet.download.manager.plus',
        'Sec-Fetch-Site': 'same-origin',
        'Sec-Fetch-Mode': 'cors',
        'Sec-Fetch-Dest': 'empty',
        'Referer': `https://www.kuwo.cn/singer_detail/${artistId}`,
        'Accept-Language': 'zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7',
        'Cookie': 'Hm_Iuvt_cdb524f42f23cer9b268564v7y735ewrq2324=cpJCjsAFxxPra6XYMwztCpNMfjC4k7wn',
      },
    });
    if (!resp.ok) return { songs: [], total: 0 };
    const json = await resp.json();
    if (json.code !== 200) return { songs: [], total: 0 };
    const total = json.data?.total || 0;
    const list = json.data?.list || [];
    const normalizeCover = (url: string) => url.replace('/120/', '/800/');
    const songs: Song[] = list.map((item: any) => {
      const rid = String(item.rid || '');
      const qualities: Quality[] = ['320k', '128k'];
      if (item.hasLossless) qualities.unshift('flac');
      return {
        id: rid,
        title: item.name || '未知歌曲',
        artist: item.artist || '未知歌手',
        cover: normalizeCover(item.pic || item.albumpic || ''),
        album: item.album || '',
        albumId: item.albumid ? String(item.albumid) : undefined,
        duration: item.duration || 0,
        platform: 'kuwo' as const,
        playUrl: '',
        lyric: undefined,
        qualities,
      };
    });
    return { songs, total };
  } catch { return { songs: [], total: 0 }; }
}

export async function getArtistAlbums(artistId: string): Promise<Album[]> {
  const cacheKey = `artist:albums:v1:${artistId}`;
  const cached = await cacheService.get<Album[]>(cacheKey);
  if (cached) return cached;

  const reqId = generateReqId();
  const url = `${API_BASE_ALBUM}/artist/artistAlbum?artistid=${artistId}&pn=1&rn=100000&httpsStatus=1&reqId=${reqId}&plat=web_www&from=`;
  try {
    const resp = await fetch(url, {
      credentials: 'omit',
      headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36',
        'Connection': 'Keep-Alive',
        'Accept': 'application/json, text/plain, */*',
        'Accept-Encoding': 'identity',
        'Cache-Control': 'no-cache, no-store, must-revalidate',
        'Origin': 'https://www.kuwo.cn',
        'Pragma': 'no-cache',
        'Range': 'bytes=0-',
        'Referer': 'https://www.kuwo.cn/',
        'sec-ch-ua': '"Android WebView";v="149", "Chromium";v="149", "Not)A;Brand";v="24"',
        'sec-ch-ua-mobile': '?1',
        'sec-ch-ua-platform': '"Android"',
      },
    });
    if (!resp.ok) return [];
    const json = await resp.json();
    if (json.code !== 200) return [];
    const list = json.data?.albumList || [];
    const albums = list.map((item: any) => ({
      albumid: String(item.albumid || ''),
      name: item.album || '未知专辑',
      artist: item.artist || '',
      musiccnt: '0',
      pub: item.releaseDate || '',
      pic: item.pic || '',
      img: '',
      hts_img: '',
      company: '',
      lang: '',
      vip: '',
      PLAYCNT: '0',
    }));
    await cacheService.set(cacheKey, albums, 30 * 1000);
    return albums;
  } catch { return []; }
}

// ── 专辑详情 ──

function v(data: string): string {
  let val = 0;
  for (let i = 0; i < data.length; i++) {
    const c = data.charCodeAt(i);
    val = ((val << 5) - val) + c;
    val &= 0x7FFFFFFF;
  }
  return val.toString();
}

function getSecret(cookie: string): string {
  const s = v(cookie);
  const keys = ['3d6b', 'b4a2', '9f7e', '2c81', 'e5f0', '1a8b', '7c3d', '6e9f'];
  const idx = parseInt(s.slice(-2), 10) % keys.length;
  return keys[idx];
}

export async function getAlbumSongs(albumId: string): Promise<{ songs: AlbumSong[]; pub: string }> {
  const cookie = `kw_token=${Date.now()}`;
  const secret = getSecret(cookie);

  const url = `http://searchlist.kuwo.cn/r.s?stype=albuminfo&albumid=${albumId}&show_copyright_off=1&alflac=1&vipver=1&sortby=1&newver=1&mobi=1`;

  const resp = await retryUntilSuccess(
    () => fetch(url, {
      headers: {
        'User-Agent': ANDROID_UA,
        'Cookie': cookie,
        'Secret': secret,
      },
    }),
    (r) => r.ok,
    20, 500,
  );
  const text = await resp.text();

  let songList: any[] = [];
  let pub = '';
  try {
    const json = JSON.parse(text);
    songList = json.musiclist || [];
    pub = json.pub || '';
  } catch {
    const match = text.match(/"musiclist"\s*:\s*(\[[\s\S]*?\])\s*(?:,"|}})/);
    if (match) {
      try { songList = JSON.parse(match[1]); } catch { songList = []; }
    }
    const pubMatch = text.match(/"pub"\s*:\s*"([^"]+)"/);
    if (pubMatch) pub = pubMatch[1];
  }

  return {
    songs: songList.map((item: any) => ({
      id: String(item.id || ''),
      name: decodeHtmlEntities(item.name || '未知歌曲'),
      artist_name: decodeHtmlEntities(item.artist || '未知歌手'),
      duration: parseInt(item.duration, 10) || 0,
      track: item.track ? parseInt(item.track, 10) : undefined,
    })),
    pub,
  };
}

const albumDetailCache = new Map<string, { songs: AlbumSong[]; pub: string }>();

export async function enrichKuwoSong(songId: string, albumId: string): Promise<{ trackNumber?: string; releaseDate?: string }> {
  if (!albumId) return {};
  try {
    if (!albumDetailCache.has(albumId)) {
      const data = await getAlbumSongs(albumId);
      albumDetailCache.set(albumId, data);
    }
    const album = albumDetailCache.get(albumId)!;
    const matched = album.songs.find((s) => s.id === songId);
    return {
      trackNumber: matched?.track ? String(matched.track) : undefined,
      releaseDate: album.pub || undefined,
    };
  } catch {
    return {};
  }
}

// ── 播放链接 ──

export async function fetchKuwoMusicUrl(songId: string, br: string): Promise<string> {
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
    () => fetchKuwoMusicUrl(songId, '320kmp3'),
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

// ── 歌词 ──

export async function searchKuwoMusicId(title: string, artist: string): Promise<string> {
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

export async function fetchKuwoLyric(songId: string, title: string, artist: string): Promise<{ lrc: string; tlyric: string | null }> {
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

  const hasCJK = (text: string): boolean => /[\u4e00-\u9fff]/.test(text);
  const hasJapaneseKana = (text: string): boolean => /[\u3040-\u309f\u30a0-\u30ff]/.test(text);
  const isTranslation = (text: string): boolean => hasCJK(text) && !hasJapaneseKana(text);
  const isOriginal = (text: string): boolean => !hasCJK(text) || hasJapaneseKana(text);

  let i = 0;
  while (i < lrclist.length) {
    const text = (lrclist[i].lineLyric || '').trim();
    if (!text) { i++; continue; }

    if (i + 1 < lrclist.length) {
      const nextText = (lrclist[i + 1].lineLyric || '').trim();
      if (nextText && isOriginal(text) && isTranslation(nextText)) {
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

// ── Kuwo secret generation (ported from Python script) ──

const KW_COOKIE_NAME = 'Hm_Iuvt_cdb524f42f23cer9b268564v7y735ewrq2324';
const KW_HARDCODED_COOKIE = 'RiGQWD34iKkZrnSYQycpmdGKAkaawJEA';

function charAt(s: string, i: number): string {
  return i >= 0 && i < s.length ? s[i] : '';
}

function generateKuwoSecret(cookieVal: string, cookieName: string = KW_COOKIE_NAME): string {
  let n = '';
  for (let i = 0; i < cookieName.length; i++) n += cookieName.charCodeAt(i).toString();
  const o = Math.floor(n.length / 5);
  const rStr = charAt(n, o) + charAt(n, 2 * o) + charAt(n, 3 * o) + charAt(n, 4 * o) + charAt(n, 5 * o);
  const r = rStr ? parseInt(rStr, 10) : 0;
  const c = Math.ceil(cookieName.length / 2);
  const l = Math.pow(2, 31) - 1;
  if (r < 2) return '';
  const d = Math.floor(1e9 * Math.random()) % 100000000;
  let nStr = n + d.toString();

  // Reduce digit string by splitting and adding (like Python's js_parse_int approach)
  // Use BigInt for precision to match Python's arbitrary precision
  const rb = BigInt(r), cb = BigInt(c), lb = BigInt(l);
  let nBig = BigInt(nStr);
  while (nBig > 9999999999n) {
    const first10 = nBig % 10000000000n;
    const rest = (nBig - first10) / 10000000000n;
    nBig = first10 + rest;
  }
  let nVal = (rb * nBig + cb) % lb;
  let h = '';
  for (let i = 0; i < cookieVal.length; i++) {
    const xorKey = Number((nVal * 255n) / lb);
    const fVal = cookieVal.charCodeAt(i) ^ xorKey;
    h += (fVal < 16 ? '0' : '') + fVal.toString(16);
    nVal = (rb * nVal + cb) % lb;
  }
  return h + d.toString(16).padStart(8, '0');
}

// ── 歌单 ──

export async function getKuwoPlaylist(playlistId: string): Promise<{
  name: string; creator: string; cover: string; songs: Song[];
} | null> {
  const songs: Song[] = [];
  let name = '', creator = '', cover = '';
  let pn = 1;
  const PAGE_SIZE = 100;

  while (true) {
    const cookieVal = KW_HARDCODED_COOKIE;
    const secret = generateKuwoSecret(cookieVal);
    const reqId = generateReqId();
    const url = `${API_BASE}/playlist/playListInfo?pid=${playlistId}&pn=${pn}&rn=${PAGE_SIZE}&httpsStatus=1&reqId=${reqId}&plat=web_www&from=`;

    try {
      let resp: Response | null = null;
      for (let attempt = 0; attempt < 3; attempt++) {
        resp = await fetch(url, {
          credentials: 'omit',
          headers: {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
            'Accept': 'application/json, text/plain, */*',
            'Referer': `https://www.kuwo.cn/playlist_detail/${playlistId}`,
            'Cookie': `${KW_COOKIE_NAME}=${cookieVal}`,
            'Secret': secret,
          },
        });
        if (resp.ok || resp.status !== 504) break;
      }
      if (!resp || !resp.ok) break;
      const json = await resp.json();
      if (json.code !== 200) break;

      const data = json.data;
      if (pn === 1) {
        name = data.name || '';
        creator = data.userName || data.uname || '';
        cover = data.img || data.pic || '';
      }

      const list = data.musicList || [];
      for (const item of list) {
        const coverUrl = (item.pic || item.albumpic || '').replace('/120/', '/800/');
        const qualities: Quality[] = ['320k', '128k'];
        if (item.hasLossless) qualities.unshift('flac');
        songs.push({
          id: String(item.rid || ''),
          title: item.name || '未知歌曲',
          artist: item.artist || '未知歌手',
          cover: coverUrl,
          album: item.album || '',
          albumId: item.albumid ? String(item.albumid) : undefined,
          duration: item.duration || 0,
          platform: 'kuwo',
          playUrl: '',
          lyric: undefined,
          tlyric: undefined,
          qualities,
        });
      }

      if (songs.length >= (data.total || 0)) break;
      pn++;
    } catch {
      break;
    }
  }

  if (songs.length === 0) return null;
  return { name, creator, cover: cover.replace('/120/', '/800/'), songs };
}

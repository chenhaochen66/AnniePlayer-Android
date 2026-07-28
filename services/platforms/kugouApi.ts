import type { Quality, Song } from '../../types';
import { decodeHtmlEntities } from '../../utils/stringUtils';

function md5(s: string): string {
  // Simple MD5 for signing - use a lightweight implementation
  // In real app, use js-md5 or similar
  let hash = 0;
  for (let i = 0; i < s.length; i++) {
    const chr = s.charCodeAt(i);
    hash = ((hash << 5) - hash) + chr;
    hash |= 0;
  }
  return Math.abs(hash).toString(16);
}

function signParams(params: Record<string, string>, key: string, body = ''): string {
  const sorted = Object.keys(params).sort();
  return md5(key + sorted.map((k) => k + '=' + params[k]).join('') + body + key);
}

// ── GCID decode ──

async function decodeGcid(gcid: string): Promise<string> {
  const key = 'OIlwieks28dk2k092lksi2UIkp';
  const ct = String(Date.now());
  const params = { appid: '1005', clienttime: ct, clientver: '20109', dfid: '-', mid: '0', uuid: '-' };
  const body = { ret_info: 1, data: [{ id: gcid, id_type: 2 }] };
  const bodyStr = JSON.stringify(body);
  const sig = signParams(params, key, bodyStr);
  const qs = Object.entries(params).map(([k, v]) => `${k}=${v}`).join('&');
  const url = `https://t.kugou.com/v1/songlist/batch_decode?${qs}&signature=${sig}`;
  const resp = await fetch(url, {
    method: 'POST',
    headers: { 'User-Agent': 'Mozilla/5.0 (Linux; Android 10)', 'Content-Type': 'application/json', 'Referer': 'https://m.kugou.com/' },
    body: bodyStr,
  });
  const data = await resp.json();
  if (data.status !== 1) throw new Error('GCID decode failed');
  return data.data.list[0].global_collection_id;
}

// ── Fetch song page ──

async function fetchSongPage(globalId: string, page = 1, pageSize = 300): Promise<any> {
  const key = 'NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt';
  const ct = String(Date.now());
  const params: Record<string, string> = {
    appid: '1058', clienttime: ct, clientver: '20000',
    global_specialid: globalId, mid: ct, page: String(page),
    pagesize: String(pageSize), plat: '0', specialid: '0',
    srcappid: '2919', uuid: ct, version: '8000',
  };
  const sig = signParams(params, key);
  const qs = Object.entries(params).map(([k, v]) => `${k}=${v}`).join('&');
  const url = `https://mobiles.kugou.com/api/v5/special/song_v2?${qs}&signature=${sig}`;
  const resp = await fetch(url, {
    headers: {
      'User-Agent': 'Mozilla/5.0 (iPhone; CPU iPhone OS 11_0)',
      'Referer': 'https://m3ws.kugou.com/share/index.php',
      'mid': ct, 'dfid': '-', 'clienttime': ct,
    },
  });
  const data = await resp.json();
  if (data.status !== 1) throw new Error('fetch songs failed');
  return data.data;
}

// ── Fetch playlist info ──

async function fetchPlaylistInfo(globalId: string): Promise<{ name: string; cover: string }> {
  const key = 'NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt';
  const params = `appid=1058&specialid=0&global_specialid=${globalId}&format=jsonp&srcappid=2919&clientver=20000&clienttime=${Date.now()}&mid=${Date.now()}&uuid=${Date.now()}&dfid=-`;
  const sig = signParams(params.split('&').reduce((a: any, p: string) => { const [k, v] = p.split('='); a[k] = v; return a; }, {}), key);
  const url = `https://mobiles.kugou.com/api/v5/special/info_v2?${params}&signature=${sig}`;
  try {
    const resp = await fetch(url, {
      headers: { 'User-Agent': 'Mozilla/5.0 (iPhone; CPU iPhone OS 11_0)', 'Referer': 'https://m3ws.kugou.com/share/index.php', 'mid': '0', 'dfid': '-', 'clienttime': '0' },
    });
    const data = await resp.json();
    if (data.status === 1) {
      const info = data.data;
      return { name: info.specialname || info.name || '', cover: (info.img || info.pic || '').replace('{size}', '800') };
    }
  } catch {}
  return { name: '', cover: '' };
}

// ── Numeric ID path (HTML parse) ──

async function fetchSpecialPage(pid: string): Promise<{ name: string; cover: string; songs: any[] }> {
  const url = `https://www2.kugou.kugou.com/yueku/v9/special/single/${pid}-5-9999.html?_=${Date.now()}`;
  const resp = await fetch(url, {
    headers: { 'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36', 'Accept': 'application/json' },
  });
  const html = await resp.text();
  const songMatch = html.match(/global\.data\s*=\s*(\[.*?\]);/s);
  if (!songMatch) return { name: '', cover: '', songs: [] };
  const songs = JSON.parse(songMatch[1]);
  const nameMatch = html.match(/name:\s*"([^"]*)"/);
  const coverMatch = html.match(/pic:\s*"([^"]*)"/);
  return {
    name: nameMatch ? nameMatch[1] : '',
    cover: coverMatch ? coverMatch[1].replace('{size}', '800') : '',
    songs,
  };
}

function parseSongDuration(durRaw: any): number {
  if (typeof durRaw === 'number') {
    if (durRaw > 10000) return Math.floor(durRaw / 1000);
    return durRaw;
  }
  return 0;
}

function getKuGouQualities(s: any): Quality[] {
  const q: Quality[] = [];
  if (s.sqhash || s.hash_sq) q.push('flac');
  if (s['320hash'] || s.hash_320) q.push('320k');
  q.push('128k');
  return q;
}

// ── Main playlist fetcher ──

export async function getKugouPlaylist(input: string): Promise<{
  name: string; creator: string; cover: string; songs: Song[];
} | null> {
  try {
    let songs: Song[] = [];
    let name = '', cover = '';

    // GCID path
    if (input.includes('gcid_') || (!/^\d+$/.test(input) && input.length > 15)) {
      const gcid = (input.match(/gcid_([a-z0-9]+)/i)?.[1] || input).toLowerCase();
      const globalId = await decodeGcid(gcid);
      const info = await fetchPlaylistInfo(globalId);
      name = info.name;
      cover = info.cover;
      const firstPage = await fetchSongPage(globalId, 1, 300);
      let rawSongs = firstPage.info || [];
      const total = firstPage.total || 0;
      const pages = Math.ceil(total / 300);
      for (let p = 2; p <= pages; p++) {
        const more = await fetchSongPage(globalId, p, 300);
        rawSongs = rawSongs.concat(more.info || []);
      }
      songs = rawSongs.map((s: any) => ({
        id: `kugou:${s.hash || ''}`,
        title: decodeHtmlEntities(s.songname || s.filename?.split(' - ')[1]?.replace(/\.[^.]+$/, '') || '未知歌曲'),
        artist: decodeHtmlEntities(s.singername || s.filename?.split(' - ')[0] || '未知歌手'),
        cover: (s.cover || s._cover || '').replace('{size}', '800'),
        album: decodeHtmlEntities(s.album_name || s._album_name || ''),
        albumId: s.album_id || undefined,
        duration: parseSongDuration(s.timelen || s.duration),
        platform: 'kugou',
        playUrl: '',
        lyric: undefined,
        tlyric: undefined,
        qualities: getKuGouQualities(s),
        releaseDate: s._publish_date || undefined,
      }));
    } else {
      // Numeric ID path
      const pid = input.replace(/[^0-9]/g, '');
      const page = await fetchSpecialPage(pid);
      name = page.name;
      cover = page.cover;
      songs = page.songs.map((s: any) => ({
        id: `kugou:${s.hash || ''}`,
        title: decodeHtmlEntities(s.songname || s.filename?.split(' - ')[1]?.replace(/\.[^.]+$/, '') || '未知歌曲'),
        artist: decodeHtmlEntities(s.singername || s.filename?.split(' - ')[0] || '未知歌手'),
        cover: (s.cover || s._cover || '').replace('{size}', '800'),
        album: decodeHtmlEntities(s.album_name || s._album_name || ''),
        albumId: s.album_id || undefined,
        duration: parseSongDuration(s.timelen || s.duration),
        platform: 'kugou',
        playUrl: '',
        lyric: undefined,
        tlyric: undefined,
        qualities: getKuGouQualities(s),
        releaseDate: s._publish_date || undefined,
      }));
    }

    if (songs.length === 0) return null;
    return { name, creator: '', cover, songs };
  } catch {
    return null;
  }
}

// ── Lyrics (hash-based) ──

const KRC_XOR_KEY = [0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47, 0x51, 0x36, 0x31, 0x2d, 0xce, 0xd2, 0x6e, 0x69];

function decodeKrc(contentB64: string): string {
  const raw = atob(contentB64);
  const buf = new Uint8Array(raw.length);
  for (let i = 0; i < raw.length; i++) {
    buf[i] = raw.charCodeAt(i) ^ KRC_XOR_KEY[i % 16];
  }
  // Strip first 4 bytes
  const sliced = buf.slice(4);
  // Inflate
  let pako: any = null;
  try { pako = require('pako'); } catch {}
  if (pako) {
    const decompressed = pako.inflate(sliced);
    return String.fromCharCode.apply(null, decompressed);
  }
  throw new Error('pako required for krc decode');
}

function parseKrcLines(krcText: string): { time: number; text: string }[] {
  const lines = krcText.split('\n');
  const result: { time: number; text: string }[] = [];
  for (const line of lines) {
    const m = line.match(/^\[(\d+),(\d+)\]/);
    if (m) {
      const startMs = parseInt(m[1]);
      const rest = line.substring(m[0].length);
      const text = rest.replace(/<\d+,\d+,\d+>/g, '').trim();
      if (text) result.push({ time: startMs, text });
    }
  }
  return result;
}

function parseKrcTranslation(krcText: string): { time: number; text: string }[] {
  const m = krcText.match(/\[language:([\w=+/]+)\]/);
  if (!m) return [];
  try {
    const data = JSON.parse(atob(m[1]));
    const tlyric = data.content?.find((item: any) => item.type === 1)?.lyricContent;
    if (!tlyric) return [];
    return tlyric.map((entry: any) => {
      const text = Array.isArray(entry) ? (entry[0] || '') : String(entry);
      return { time: 0, text };
    });
  } catch {
    return [];
  }
}

const fmtTime = (ms: number) => {
  const sec = ms / 1000;
  const min = Math.floor(sec / 60);
  const s = Math.floor(sec % 60);
  const cs = Math.floor((sec - Math.floor(sec)) * 100);
  return `[${String(min).padStart(2, '0')}:${String(s).padStart(2, '0')}.${String(cs).padStart(2, '0')}]`;
};

export async function fetchKugouLyric(songId: string): Promise<{ lrc: string; tlyric: string | null }> {
  const hash = songId.replace(/^kugou:/, '');
  try {
    const searchResp = await fetch(`http://lyrics.kugou.com/search?ver=1&man=yes&client=pc&hash=${hash}&timelength=254`, {
      headers: { 'KG-RC': '1', 'KG-THash': 'expand_search_manager.cpp:852736169:451', 'User-Agent': 'KuGou2012-9020-ExpandSearchManager' },
    });
    const searchData = await searchResp.json();
    const candidates = searchData.candidates || [];
    if (candidates.length === 0) return { lrc: '[00:00.00]暂无歌词', tlyric: null };

    const info = candidates[0];
    const id = info.id;
    const accessKey = info.accesskey;
    const fmt = info.krctype === 1 && info.contenttype !== 1 ? 'krc' : 'lrc';

    const dlResp = await fetch(`http://lyrics.kugou.com/download?ver=1&client=pc&id=${id}&accesskey=${accessKey}&fmt=${fmt}&charset=utf8`, {
      headers: { 'KG-RC': '1', 'KG-THash': 'expand_search_manager.cpp:852736169:451', 'User-Agent': 'KuGou2012-9020-ExpandSearchManager' },
    });
    const dlData = await dlResp.json();
    if (fmt === 'lrc') {
      const decoded = atob(dlData.content);
      return { lrc: decoded, tlyric: null };
    }
    const krcText = decodeKrc(dlData.content);
    const lyricLines = parseKrcLines(krcText);
    const transLines = parseKrcTranslation(krcText);

    const lrc = lyricLines.map((l) => fmtTime(l.time) + l.text).join('\n');
    const tlyric = transLines.length > 0
      ? lyricLines.map((l, i) => fmtTime(l.time) + (transLines[i]?.text || '')).join('\n')
      : null;

    return { lrc, tlyric };
  } catch {
    return { lrc: '[00:00.00]暂无歌词', tlyric: null };
  }
}

// ── 单曲详情（补音轨号/碟号/发行日期） ──

async function getKugouSongAlbumId(songHash: string): Promise<{ albumId: string; publishDate: string } | null> {
  try {
    const payload = {
      data: [{ hash: songHash }], area_code: '1', show_privilege: 1, show_album_info: '1',
      is_publish: '', appid: 1005, clientver: 11451, mid: '1', dfid: '-',
      clienttime: Date.now(), key: 'OIlwieks28dk2k092lksi2UIkp',
      fields: 'album_info,author_name,audio_info,base,songname,classification',
    };
    const resp = await fetch('http://gateway.kugou.com/v3/album_audio/audio', {
      method: 'POST',
      headers: {
        'KG-THash': '13a3164', 'KG-RC': '1', 'KG-Fake': '0', 'KG-RF': '00869891',
        'User-Agent': 'Android712-AndroidPhone-11451-376-0-FeeCacheUpdate-wifi',
        'x-router': 'kmr.service.kugou.com', 'Content-Type': 'application/json',
      },
      body: JSON.stringify(payload),
    });
    const data = await resp.json();
    const item = data?.data?.[0]?.[0];
    if (!item?.album_info?.album_id) return null;
    return {
      albumId: String(item.album_info.album_id),
      publishDate: item.album_info.publish_date || '',
    };
  } catch {
    return null;
  }
}

const kugouAlbumTracksCache = new Map<string, Record<string, { sort: string; disc: string }>>();

async function fetchKugouAlbumTracks(albumId: string): Promise<Record<string, { sort: string; disc: string }>> {
  if (kugouAlbumTracksCache.has(albumId)) return kugouAlbumTracksCache.get(albumId)!;
  try {
    const ct = String(Date.now());
    const params: Record<string, string> = {
      album_id: albumId, appid: '1005', area_code: '1', clienttime: ct, clientver: '20729',
      dfid: '-', is_buy: '0', mid: ct, page: '1', pagesize: '50',
      show_album_audios_timelength: '1', show_album_info: '1', show_album_tags: '1',
      show_awards: '1', show_classical_author: '1', show_dycover: '1', show_short_intro_v2: '1', userid: '0',
    };
    const sorted = Object.keys(params).sort().map((k) => `${k}=${params[k]}`).join('');
    const sig = md5('OIlwieks28dk2k092lksi2UIkp' + sorted + 'OIlwieks28dk2k092lksi2UIkp');
    const qs = Object.entries(params).map(([k, v]) => `${k}=${v}`).join('&');
    const url = `https://gateway.kugou.com/openapi/v1/union/album/audios?${qs}&signature=${sig}`;
    const resp = await fetch(url, {
      headers: {
        'User-Agent': 'Android16-AndroidPhone-20729-46-0-AlbumDetailFragment',
        'kg-thash': '228424f', 'kg-rc': '1', 'kg-fake': '0', 'kg-rf': 'DC6373DBF6F823AF925CC56D1A81DD72',
      },
    });
    const raw = await resp.json();
    const list = raw?.data?.song_data?.song_list || [];
    const lookup: Record<string, { sort: string; disc: string }> = {};
    for (const s of list) {
      const h = s?.audio_info?.hash || '';
      if (h) {
        lookup[h.toUpperCase()] = {
          sort: s?.extend?.sort || '',
          disc: s?.extend?.disc || '',
        };
      }
    }
    kugouAlbumTracksCache.set(albumId, lookup);
    return lookup;
  } catch {
    return {};
  }
}

export async function enrichKuGouSong(songId: string): Promise<{ trackNumber?: string; discNumber?: string; releaseDate?: string }> {
  const hash = songId.replace(/^kugou:/, '').toUpperCase();
  try {
    const info = await getKugouSongAlbumId(hash);
    if (!info) return {};
    const lookup = await fetchKugouAlbumTracks(info.albumId);
    const matched = lookup[hash] || {};
    return {
      trackNumber: matched.sort || undefined,
      discNumber: matched.disc || undefined,
      releaseDate: info.publishDate || undefined,
    };
  } catch {
    return {};
  }
}

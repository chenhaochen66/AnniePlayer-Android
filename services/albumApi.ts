import type { Album, AlbumSong } from '../types';
import { retryUntilSuccess } from '../utils/retry';
import { decodeHtmlEntities } from '../utils/stringUtils';

const ANDROID_UA = 'Mozilla/5.0 (Linux; Android 14; SM-S928B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36';

export function buildAlbumCoverUrl(album: Album): string {
  // pic="300/s4s86/95/3059703046.jpg"; hts_img="https://img4.kuwo.cn/star/albumcover/240/s4s86/95/..."
  const raw = album.pic || album.hts_img || album.img || '';
  if (!raw) return '';
  if (/^\d+\//.test(raw)) {
    return `http://img1.kuwo.cn/star/albumcover/500/${raw.replace(/^\d+\//, '')}`;
  }
  if (raw.startsWith('http')) {
    const m = raw.match(/\/star\/albumcover\/\d+\/(.+)/);
    if (m) return `http://img1.kuwo.cn/star/albumcover/500/${m[1]}`;
  }
  return raw;
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

function v(data: string): string {
  let v = 0;
  for (let i = 0; i < data.length; i++) {
    const c = data.charCodeAt(i);
    v = ((v << 5) - v) + c;
    v &= 0x7FFFFFFF;
  }
  return v.toString();
}

function getSecret(cookie: string): string {
  const s = v(cookie);
  const keys = ['3d6b', 'b4a2', '9f7e', '2c81', 'e5f0', '1a8b', '7c3d', '6e9f'];
  const idx = parseInt(s.slice(-2), 10) % keys.length;
  return keys[idx];
}

export async function getAlbumSongs(albumId: string): Promise<AlbumSong[]> {
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
  try {
    const json = JSON.parse(text);
    songList = json.musiclist || [];
  } catch {
    const match = text.match(/"musiclist"\s*:\s*(\[[\s\S]*?\])\s*(?:,"|}})/);
    if (match) {
      try { songList = JSON.parse(match[1]); } catch { songList = []; }
    }
  }

  return songList.map((item: any) => ({
    id: String(item.id || ''),
    name: decodeHtmlEntities(item.name || '未知歌曲'),
    artist_name: decodeHtmlEntities(item.artist || '未知歌手'),
    duration: parseInt(item.duration, 10) || 0,
  }));
}

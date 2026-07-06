import type { Artist, Song, Quality, Album } from '../types';
import { cacheService } from './cacheService';
import { decodeHtmlEntities } from '../utils/stringUtils';

const API_BASE = 'https://www.kuwo.cn/api/www';
const API_BASE_ALBUM = 'https://wapi.kuwo.cn/api/www';
const SEARCH_CACHE_TTL = 30 * 1000;

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

// ── 歌手搜索（对应 curl #1） ──

async function searchArtistNew(keyword: string): Promise<Artist[] | null> {
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
    if (!resp.ok) return null;
    const json = await resp.json();
    if (json.code !== 200) return null;
    const list = json.data?.artistList || json.data?.list || [];
    return list.map((item: any) => ({
      id: String(item.id || ''),
      name: decodeHtmlEntities(item.name || '未知歌手'),
      picture: item.pic300 || item.pic || '',
      musicNum: item.musicNum || 0,
    }));
  } catch {
    return null;
  }
}

export async function searchArtist(keyword: string): Promise<Artist[]> {
  const cacheKey = `artist:search:v1:${keyword}`;
  const cached = await cacheService.get<Artist[]>(cacheKey);
  if (cached) return cached;

  const artists = await searchArtistNew(keyword);
  if (artists && artists.length > 0) {
    await cacheService.set(cacheKey, artists, SEARCH_CACHE_TTL);
  }
  return artists || [];
}

// ── 歌手歌曲（对应 curl #2） ──

async function getArtistSongsNew(artistId: string, page: number): Promise<{ songs: Song[]; total: number } | null> {
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
    if (!resp.ok) return null;
    const json = await resp.json();
    if (json.code !== 200) return null;
    const total = json.data?.total || 0;
    const list = json.data?.list || [];
    const songs: Song[] = list.map((item: any) => {
      const rid = String(item.rid || '');
      const qualities: Quality[] = ['320k', '128k'];
      if (item.hasLossless) qualities.unshift('flac');
      return {
        id: rid,
        title: item.name || '未知歌曲',
        artist: item.artist || '未知歌手',
        cover: item.pic || item.albumpic || '',
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
  } catch { return null; }
}

export async function getArtistSongs(artistId: string, page: number = 1): Promise<{ songs: Song[]; total: number }> {
  const result = await getArtistSongsNew(artistId, page);
  return result || { songs: [], total: 0 };
}

// ── 歌手专辑（对应 curl #3） ──

async function getArtistAlbumsNew(artistId: string): Promise<Album[] | null> {
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
    if (!resp.ok) return null;
    const json = await resp.json();
    if (json.code !== 200) return null;
    const list = json.data?.albumList || [];
    return list.map((item: any) => ({
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
  } catch { return null; }
}

export async function getArtistAlbums(artistId: string): Promise<Album[]> {
  const cacheKey = `artist:albums:v1:${artistId}`;
  const cached = await cacheService.get<Album[]>(cacheKey);
  if (cached) return cached;

  const albums = await getArtistAlbumsNew(artistId);
  if (albums && albums.length > 0) {
    await cacheService.set(cacheKey, albums, SEARCH_CACHE_TTL);
  }
  return albums || [];
}

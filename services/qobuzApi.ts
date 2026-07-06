const APP_ID = '312369995';
const APP_SECRET = 'e79f8b9be485692b0e5f9dd895826368';
const BASE_URL = 'https://www.qobuz.com/api.json/0.2/';

function md5(s: string): string {
  const hex_chr = '0123456789abcdef';
  function rhex(n: number): string {
    let str = '';
    for (let j = 0; j < 4; j++)
      str += hex_chr.charAt((n >> (j * 8 + 4)) & 0x0f) + hex_chr.charAt((n >> (j * 8)) & 0x0f);
    return str;
  }
  function str2blks(s: string): number[] {
    const nblk = ((s.length + 8) >> 6) + 1;
    const blks = new Array(nblk * 16).fill(0);
    for (let i = 0; i < s.length; i++)
      blks[i >> 2] |= s.charCodeAt(i) << ((i % 4) * 8);
    blks[s.length >> 2] |= 0x80 << ((s.length % 4) * 8);
    blks[nblk * 16 - 2] = s.length * 8;
    return blks;
  }
  function add(x: number, y: number): number {
    const lsw = (x & 0xFFFF) + (y & 0xFFFF);
    return (((x >> 16) + (y >> 16) + (lsw >> 16)) << 16) | (lsw & 0xFFFF);
  }
  function rol(n: number, c: number): number { return (n << c) | (n >>> (32 - c)); }
  function cmn(q: number, a: number, b: number, x: number, s: number, t: number): number { return add(rol(add(add(a, q), add(x, t)), s), b); }
  function ff(a: number, b: number, c: number, d: number, x: number, s: number, t: number): number { return cmn((b & c) | (~b & d), a, b, x, s, t); }
  function gg(a: number, b: number, c: number, d: number, x: number, s: number, t: number): number { return cmn((b & d) | (c & ~d), a, b, x, s, t); }
  function hh(a: number, b: number, c: number, d: number, x: number, s: number, t: number): number { return cmn(b ^ c ^ d, a, b, x, s, t); }
  function ii(a: number, b: number, c: number, d: number, x: number, s: number, t: number): number { return cmn(c ^ (b | ~d), a, b, x, s, t); }

  const x = str2blks(s);
  let a = 0x67452301, b = 0xEFCDAB89, c = 0x98BADCFE, d = 0x10325476;

  for (let i = 0; i < x.length; i += 16) {
    const olda = a, oldb = b, oldc = c, oldd = d;
    a = ff(a, b, c, d, x[i+0], 7, 0xD76AA478); d = ff(d, a, b, c, x[i+1], 12, 0xE8C7B756); c = ff(c, d, a, b, x[i+2], 17, 0x242070DB); b = ff(b, c, d, a, x[i+3], 22, 0xC1BDCEEE);
    a = ff(a, b, c, d, x[i+4], 7, 0xF57C0FAF); d = ff(d, a, b, c, x[i+5], 12, 0x4787C62A); c = ff(c, d, a, b, x[i+6], 17, 0xA8304613); b = ff(b, c, d, a, x[i+7], 22, 0xFD469501);
    a = ff(a, b, c, d, x[i+8], 7, 0x698098D8); d = ff(d, a, b, c, x[i+9], 12, 0x8B44F7AF); c = ff(c, d, a, b, x[i+10], 17, 0xFFFF5BB1); b = ff(b, c, d, a, x[i+11], 22, 0x895CD7BE);
    a = ff(a, b, c, d, x[i+12], 7, 0x6B901122); d = ff(d, a, b, c, x[i+13], 12, 0xFD987193); c = ff(c, d, a, b, x[i+14], 17, 0xA679438E); b = ff(b, c, d, a, x[i+15], 22, 0x49B40821);
    a = gg(a, b, c, d, x[i+1], 5, 0xF61E2562); d = gg(d, a, b, c, x[i+6], 9, 0xC040B340); c = gg(c, d, a, b, x[i+11], 14, 0x265E5A51); b = gg(b, c, d, a, x[i+0], 20, 0xE9B6C7AA);
    a = gg(a, b, c, d, x[i+5], 5, 0xD62F105D); d = gg(d, a, b, c, x[i+10], 9, 0x02441453); c = gg(c, d, a, b, x[i+15], 14, 0xD8A1E681); b = gg(b, c, d, a, x[i+4], 20, 0xE7D3FBC8);
    a = gg(a, b, c, d, x[i+9], 5, 0x21E1CDE6); d = gg(d, a, b, c, x[i+14], 9, 0xC33707D6); c = gg(c, d, a, b, x[i+3], 14, 0xF4D50D87); b = gg(b, c, d, a, x[i+8], 20, 0x455A14ED);
    a = gg(a, b, c, d, x[i+13], 5, 0xA9E3E905); d = gg(d, a, b, c, x[i+2], 9, 0xFCEFA3F8); c = gg(c, d, a, b, x[i+7], 14, 0x676F02D9); b = gg(b, c, d, a, x[i+12], 20, 0x8D2A4C8A);
    a = hh(a, b, c, d, x[i+5], 4, 0xFFFA3942); d = hh(d, a, b, c, x[i+8], 11, 0x8771F681); c = hh(c, d, a, b, x[i+11], 16, 0x6D9D6122); b = hh(b, c, d, a, x[i+14], 23, 0xFDE5380C);
    a = hh(a, b, c, d, x[i+1], 4, 0xA4BEEA44); d = hh(d, a, b, c, x[i+4], 11, 0x4BDECFA9); c = hh(c, d, a, b, x[i+7], 16, 0xF6BB4B60); b = hh(b, c, d, a, x[i+10], 23, 0xBEBFBC70);
    a = hh(a, b, c, d, x[i+13], 4, 0x289B7EC6); d = hh(d, a, b, c, x[i+0], 11, 0xEAA127FA); c = hh(c, d, a, b, x[i+3], 16, 0xD4EF3085); b = hh(b, c, d, a, x[i+6], 23, 0x04881D05);
    a = hh(a, b, c, d, x[i+9], 4, 0xD9D4D039); d = hh(d, a, b, c, x[i+12], 11, 0xE6DB99E5); c = hh(c, d, a, b, x[i+15], 16, 0x1FA27CF8); b = hh(b, c, d, a, x[i+2], 23, 0xC4AC5665);
    a = ii(a, b, c, d, x[i+0], 6, 0xF4292244); d = ii(d, a, b, c, x[i+7], 10, 0x432AFF97); c = ii(c, d, a, b, x[i+14], 15, 0xAB9423A7); b = ii(b, c, d, a, x[i+5], 21, 0xFC93A039);
    a = ii(a, b, c, d, x[i+12], 6, 0x655B59C3); d = ii(d, a, b, c, x[i+3], 10, 0x8F0CCC92); c = ii(c, d, a, b, x[i+10], 15, 0xFFEFF47D); b = ii(b, c, d, a, x[i+1], 21, 0x85845DD1);
    a = ii(a, b, c, d, x[i+8], 6, 0x6FA87E4F); d = ii(d, a, b, c, x[i+15], 10, 0xFE2CE6E0); c = ii(c, d, a, b, x[i+6], 15, 0xA3014314); b = ii(b, c, d, a, x[i+13], 21, 0x4E0811A1);
    a = ii(a, b, c, d, x[i+4], 6, 0xF7537E82); d = ii(d, a, b, c, x[i+11], 10, 0xBD3AF235); c = ii(c, d, a, b, x[i+2], 15, 0x2AD7D2BB); b = ii(b, c, d, a, x[i+9], 21, 0xEB86D391);
    a = add(a, olda); b = add(b, oldb); c = add(c, oldc); d = add(d, oldd);
  }
  return rhex(a) + rhex(b) + rhex(c) + rhex(d);
}

function createSignature(method: string, params: Record<string, string>): { ts: string; sig: string } {
  const ts = Math.floor(Date.now() / 1000).toString();
  const methodKey = method.replace('/', '');
  const keys = Object.keys(params).filter((k) => k !== 'app_id' && k !== 'user_auth_token').sort();
  let raw = methodKey;
  for (const k of keys) raw += k + (params[k] || '');
  raw += ts + APP_SECRET;
  return { ts, sig: md5(raw) };
}

function headers(token: string): Record<string, string> {
  return {
    'X-Device-Platform': 'android',
    'X-Device-Model': 'Pixel 3',
    'X-Device-Os-Version': '10',
    'X-User-Auth-Token': token,
    'X-Device-Manufacturer-Id': '482D8CB7-015D-402F-A93B-5EEF0E0996F3',
    'X-App-Version': '5.16.1.5',
    'User-Agent': 'Dalvik/2.1.0 (Linux; U; Android 10; Pixel 3 Build/QP1A.190711.020))QobuzMobileAndroid/5.16.1.5-b21041415',
  };
}

export function parseQobuzUrl(text: string): { type: 'track' | 'album'; id: string } | null {
  const re = /https?:\/\/open\.qobuz\.com\/(track|album)\/([a-zA-Z0-9]+)/;
  const m = text.match(re);
  if (!m) return null;
  return { type: m[1] as 'track' | 'album', id: m[2] };
}

export async function checkToken(token: string): Promise<{ region: string; valid: boolean }> {
  const params: Record<string, string> = { app_id: APP_ID };
  const { ts, sig } = createSignature('user/get', params);
  const url = `${BASE_URL}user/get?app_id=${APP_ID}&request_ts=${ts}&request_sig=${sig}`;
  const resp = await fetch(url, { headers: headers(token) });
  if (!resp.ok) {
    if (resp.status === 401) return { region: '', valid: false };
    throw new Error(`HTTP ${resp.status}`);
  }
  const data = await resp.json();
  const region = data?.region ?? data?.country ?? '';
  return { region, valid: !!region };
}

export async function getTrack(trackId: string, token: string): Promise<any> {
  const params: Record<string, string> = { app_id: APP_ID, track_id: trackId };
  const { ts, sig } = createSignature('track/get', params);
  const url = `${BASE_URL}track/get?app_id=${APP_ID}&track_id=${trackId}&request_ts=${ts}&request_sig=${sig}`;
  const resp = await fetch(url, { headers: headers(token) });
  if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
  return resp.json();
}

export async function getAlbum(albumId: string, token: string): Promise<any> {
  const params: Record<string, string> = { app_id: APP_ID, album_id: albumId };
  const { ts, sig } = createSignature('album/get', params);
  const url = `${BASE_URL}album/get?app_id=${APP_ID}&album_id=${albumId}&request_ts=${ts}&request_sig=${sig}`;
  const resp = await fetch(url, { headers: headers(token) });
  if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
  return resp.json();
}

export async function getFileUrl(trackId: string, qualityId: number, token: string): Promise<{ url: string; mimeType: string }> {
  const params: Record<string, string> = {
    app_id: APP_ID,
    track_id: trackId,
    format_id: String(qualityId),
    intent: 'stream',
    sample: 'false',
    user_auth_token: token,
  };
  const { ts, sig } = createSignature('track/getFileUrl', params);
  const allParams: Record<string, string> = {
    ...params,
    request_ts: ts,
    request_sig: sig,
  };
  const qs = Object.keys(allParams).map((k) => `${k}=${encodeURIComponent(allParams[k])}`).join('&');
  const url = `${BASE_URL}track/getFileUrl?${qs}`;
  const resp = await fetch(url, { headers: headers(token) });
  if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
  const data = await resp.json();
  return { url: data.url, mimeType: data.mime_type || 'audio/flac' };
}

export function mapTrackToQobuzTrack(raw: any): import('../types').QobuzTrack {
  const performerName = typeof raw.performer === 'object' && raw.performer ? raw.performer.name : raw.performer;
  const artistName = typeof raw.artist === 'object' && raw.artist ? raw.artist.name : raw.artist;
  return {
    id: raw.id,
    title: raw.title || '',
    artist: String(performerName || artistName || ''),
    cover: raw.album?.image?.large || raw.image?.large || '',
    album: raw.album?.title || '',
    albumId: raw.album?.id || '',
    trackNumber: raw.track_number || 0,
    duration: raw.duration || 0,
    streamable: !!raw.streamable,
    label: raw.label || '',
    year: raw.year || 0,
  };
}

export function mapAlbumToQobuzAlbum(raw: any): import('../types').QobuzAlbum {
  const cover = raw.image?.large || raw.album?.image?.large || '';
  const artistName = typeof raw.artist === 'object' && raw.artist ? raw.artist.name : raw.artist;
  const performerName = typeof raw.performer === 'object' && raw.performer ? raw.performer.name : raw.performer;
  return {
    id: raw.id,
    title: raw.title || '',
    artist: String(artistName || performerName || ''),
    cover,
    year: raw.year || 0,
    label: raw.label || '',
    tracks: (raw.tracks?.items || []).map((t: any) => mapTrackToQobuzTrack({
      ...t,
      album: { id: raw.id, title: raw.title, image: { large: cover } },
      performer: { name: artistName || performerName || '' },
    })),
  };
}

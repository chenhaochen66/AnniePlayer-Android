import * as FileSystem from 'expo-file-system/legacy';
import { requireNativeModule } from 'expo-modules-core';

let nativeDecoder: { decryptKkboxAudio: (url: string, key: string, path: string) => Promise<string> } | null = null;
try {
  nativeDecoder = requireNativeModule('KkboxDecoder') as any;
} catch {} // not available on web / before rebuild

const KC1_KEY = '7f1a68f00b747f4ac1469c72e7ef492c';
const SECRET_KEY = '0ff29b7c9bd8fb60a3abd6b3d402b02c';

class Rc4Stream {
  private s: Uint8Array;
  private i = 0;
  private j = 0;

  constructor(key: string, drop = 0) {
    const keyBytes = key.split('').map((c) => c.charCodeAt(0));
    this.s = new Uint8Array(256);
    for (let i = 0; i < 256; i++) this.s[i] = i;
    for (let i = 0, j = 0; i < 256; i++) {
      j = (j + this.s[i] + keyBytes[i % keyBytes.length]) & 0xff;
      [this.s[i], this.s[j]] = [this.s[j], this.s[i]];
    }
    for (let n = 0; n < drop; n++) {
      this.i = (this.i + 1) & 0xff;
      this.j = (this.j + this.s[this.i]) & 0xff;
      [this.s[this.i], this.s[this.j]] = [this.s[this.j], this.s[this.i]];
    }
  }

  process(input: Uint8Array): Uint8Array {
    const output = new Uint8Array(input.length);
    for (let n = 0; n < input.length; n++) {
      this.i = (this.i + 1) & 0xff;
      this.j = (this.j + this.s[this.i]) & 0xff;
      [this.s[this.i], this.s[this.j]] = [this.s[this.j], this.s[this.i]];
      output[n] = (input[n] ^ this.s[(this.s[this.i] + this.s[this.j]) & 0xff]);
    }
    return output;
  }
}

function rc4(key: string, data: ArrayBuffer, drop?: number): ArrayBuffer {
  const stream = new Rc4Stream(key, drop || 0);
  return stream.process(new Uint8Array(data)).buffer as ArrayBuffer;
}

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

function createSignature(): { secret: string; timestamp: number } {
  const timestamp = Math.floor(Date.now() / 1000);
  const m = md5('06120082' + timestamp + SECRET_KEY);
  return { secret: m, timestamp };
}

function getBaseParams(sid?: string): Record<string, string> {
  const { secret, timestamp } = createSignature();
  return {
    enc: 'u', ver: '06120082', os: 'android', osver: '13',
    lang: 'en', ui_lang: 'en', dist: '0021', dist2: '0021',
    resolution: '411x841', of: 'j', oenc: 'kc1',
    secret, timestamp: String(timestamp),
    ...(sid ? { sid } : {}),
  };
}

async function apiCall<T>(host: string, path: string, params: Record<string, string> = {}, payload?: any): Promise<T> {
  const allParams = { ...getBaseParams(params.sid), ...params };
  const qs = Object.entries(allParams).map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`).join('&');
  const url = `https://api-${host}.kkbox.com.tw/${path}?${qs}`;
  let body: string | undefined;
  const headers: Record<string, string> = { 'User-Agent': 'okhttp/3.14.9' };
  if (payload) {
    if (typeof payload === 'string') {
      body = payload;
    } else {
      const form = new URLSearchParams();
      for (const [k, v] of Object.entries(payload)) form.append(k, String(v));
      body = form.toString();
      headers['Content-Type'] = 'application/x-www-form-urlencoded';
    }
  }
  const resp = await fetch(url, {
    method: payload ? 'POST' : 'GET',
    headers,
    body,
  });
  const buf = await resp.arrayBuffer();
  const decrypted = rc4(KC1_KEY, buf);
  const decoder = new TextDecoder('utf-8');
  return JSON.parse(decoder.decode(decrypted));
}

export function parseKkboxUrl(text: string): { type: 'song' | 'album'; id: string } | null {
  const patterns: [RegExp, 'song' | 'album'][] = [
    [/\/song[/-]([A-Za-z0-9\-_\.]+)/, 'song'],
    [/\/album[/-]([A-Za-z0-9\-_\.]+)/, 'album'],
    [/\/playlist[/-]([A-Za-z0-9\-_\.]+)/, 'song'],
  ];
  for (const [re, type] of patterns) {
    const m = text.match(re);
    if (m) return { type, id: m[1] };
  }
  if (/^[A-Za-z0-9\-_\.]+$/.test(text.trim())) return { type: 'song', id: text.trim() };
  return null;
}

const QUALITY_FORMAT_MAP: Record<string, string> = {
  '128k': 'mp3_128k_chromecast',
  '192k': 'mp3_192k_kkdrm1',
  '320k': 'aac_320k_m4a_kkdrm1',
  'hifi': 'flac_16_download_kkdrm',
  'hires': 'flac_24_download_kkdrm',
};
const QUALITY_ORDER = ['hires', 'hifi', '320k', '192k', '128k'];
const FORMAT_TO_QUALITY: Record<string, string> = {};
for (const [q, f] of Object.entries(QUALITY_FORMAT_MAP)) FORMAT_TO_QUALITY[f] = q;

export async function login(email: string, password: string): Promise<{
  sid: string; licContentKey: string; highQuality: boolean; kkid: string;
}> {
  const kkid = Array.from({ length: 32 }, () => Math.floor(Math.random() * 16).toString(16)).join('').toUpperCase();
  const passwd = md5(password);
  const resp = await apiCall<any>('login', 'login.php', {}, {
    uid: email, passwd, kkid, registration_id: '',
  });
  if (resp.status !== 2 && resp.status !== 3) {
    const errors: Record<number, string> = {
      [-1]: '邮箱不存在', [-2]: '密码错误', [-4]: 'IP 地区不支持，请使用 VPN', [1]: '账户已过期',
    };
    throw new Error(errors[resp.status] || `登录失败: ${resp.status}`);
  }
  return {
    sid: resp.sid, licContentKey: resp.lic_content_key,
    highQuality: !!resp.high_quality, kkid,
  };
}

export async function renewSession(sid: string): Promise<{ sid: string; licContentKey: string; highQuality: boolean }> {
  const resp = await apiCall<any>('login', 'check.php', { sid });
  if (resp.status !== 2 && resp.status !== 3) throw new Error('会话续期失败');
  return { sid: resp.sid, licContentKey: resp.lic_content_key, highQuality: !!resp.high_quality };
}

export async function authDevice(sid: string, kkid: string): Promise<void> {
  const resp = await apiCall<any>('ds', 'active_sid.php', { sid }, {
    ui_lang: 'en', of: 'j', os: 'android', enc: 'u',
    sid, ver: '06120082', kkid, lang: 'en', oenc: 'kc1', osver: '13',
  });
  if (resp.status !== 1) throw new Error('设备认证失败');
}

export async function getSongs(sid: string, ids: string[]): Promise<any[]> {
  const resp = await apiCall<any>('ds', 'v2/song', { sid }, {
    ids: ids.join(','),
    fields: 'song_id,song_name,artist_name,album_name,artist_role,song_idx,album_photo_info,song_is_explicit,song_more_url,album_more_url,artist_more_url,genre_name,is_lyrics,audio_quality,album_date',
  });
  if (resp.status?.type !== 'OK') throw new Error('获取歌曲信息失败');
  return resp.data?.songs || [];
}

export async function getAlbum(sid: string, albumId: string): Promise<{
  album: any; songs: any[];
}> {
  const resp = await apiCall<any>('ds', `v1/album/${albumId}`, { sid });
  if (resp.status?.type !== 'OK') throw new Error('获取专辑信息失败');
  return { album: resp.data?.album || {}, songs: resp.data?.songs || [] };
}

export async function getTicket(sid: string, songId: string, kkid: string, playMode: string | null = null, retried = false): Promise<{ uris: any[]; sid: string }> {
  const timestamp = Math.floor(Date.now() / 1000);
  const resp = await apiCall<any>('ticket', 'v1/ticket', { sid }, JSON.stringify({
    sid, song_id: String(songId), ver: '06120082',
    os: 'android', osver: '13', kkid,
    dist: '0021', dist2: '0021', timestamp,
    play_mode: playMode,
  }));
  if (resp.status !== 1) {
    if (resp.status === -1) {
      const renewed = await renewSession(sid);
      return getTicket(renewed.sid, songId, kkid, playMode);
    }
    if (resp.status === -4) {
      await authDevice(sid, kkid);
      return getTicket(sid, songId, kkid, playMode);
    }
    if (resp.status === 2) {
      await new Promise((r) => setTimeout(r, 500));
      return getTicket(sid, songId, kkid, playMode);
    }
    if (resp.status === -2) {
      if (retried) throw new Error(`获取播放票据失败(status=-2): ${JSON.stringify(resp).slice(0, 200)}`);
      return getTicket(sid, songId, kkid, playMode, true);
    }
    if (retried) throw new Error(`获取播放票据失败(status=${resp.status})`);
    return getTicket(sid, songId, kkid, playMode, true);
  }
  return { uris: resp.uris || [], sid };
}

export async function getPlayUrl(sid: string, songId: string, kkid: string): Promise<{ url: string; sid: string }> {
  const { uris, sid: newSid } = await getTicket(sid, songId, kkid, 'chromecast');
  const formatName = 'mp3_128k_chromecast';
  const found = uris.find((u: any) => u.name === formatName);
  const url = found ? found.url : (uris.find((u: any) => u.name?.includes('chromecast'))?.url || uris[0]?.url || '');
  return { url, sid: newSid };
}

export async function getDownloadUrl(sid: string, songId: string, kkid: string, quality: string): Promise<{ url: string; formatName: string; sid: string }> {
  const requestedFormat = QUALITY_FORMAT_MAP[quality] || 'flac_24_download_kkdrm';
  const playMode = requestedFormat === 'mp3_128k_chromecast' ? 'chromecast' : null;
  const { uris, sid: newSid } = await getTicket(sid, songId, kkid, playMode);
  const found = uris.find((u: any) => u.name === requestedFormat);
  if (found) return { url: found.url, formatName: found.name, sid: newSid };
  for (const q of QUALITY_ORDER) {
    if (q === quality) continue;
    const fn = QUALITY_FORMAT_MAP[q];
    const f = uris.find((u: any) => u.name === fn);
    if (f) return { url: f.url, formatName: f.name, sid: newSid };
  }
  return { url: uris[0]?.url || '', formatName: uris[0]?.name || '', sid: newSid };
}

export function decryptAudio(data: ArrayBuffer, licContentKey: string): ArrayBuffer {
  return rc4(licContentKey, data, 512);
}

export function formatNameToQuality(formatName: string): string {
  return FORMAT_TO_QUALITY[formatName] || '128k';
}

export function isDrmFormat(formatName: string): boolean {
  return formatName !== 'mp3_128k_chromecast';
}

export function qualityExtension(quality: string): string {
  if (quality === 'hifi' || quality === 'hires') return '.flac';
  if (quality === '320k') return '.m4a';
  return '.mp3';
}

const CHROME_UA = 'okhttp/3.14.9';

export async function downloadKkboxAudio(url: string, licContentKey: string): Promise<ArrayBuffer> {
  const resp = await fetch(url, {
    headers: { 'User-Agent': CHROME_UA, 'Range': 'bytes=1024-' },
  });
  if (!resp.ok) throw new Error(`下载失败: ${resp.status}`);
  const buf = await resp.arrayBuffer();
  return decryptAudio(buf, licContentKey);
}

const CHUNK_3 = 49152; // 16384*3, multiple of 3 for clean base64

function u8ToBase64Chunk(bytes: Uint8Array, start: number, end: number): string {
  let bin = '';
  for (let i = start; i < end; i++) bin += String.fromCharCode(bytes[i]);
  return btoa(bin);
}

export async function streamDecryptKkboxAudio(
  url: string, licContentKey: string, outputPath: string,
  onProgress?: (p: number) => void,
): Promise<void> {
  onProgress?.(0.1);

  if (nativeDecoder) {
    const nativePath = outputPath.replace(/^file:\/\//, '');
    await nativeDecoder.decryptKkboxAudio(url, licContentKey, nativePath);
    onProgress?.(1);
    return;
  }

  // Fallback JS version (synchronous, blocks UI)
  const resp = await fetch(url, {
    headers: { 'User-Agent': CHROME_UA, 'Range': 'bytes=1024-' },
  });
  if (!resp.ok) throw new Error(`下载失败: ${resp.status}`);
  const encrypted = await resp.arrayBuffer();
  const bytes = new Uint8Array(encrypted);
  onProgress?.(0.4);

  const stream = new Rc4Stream(licContentKey, 512);
  let b64 = '';

  for (let offset = 0; offset < bytes.length; offset += CHUNK_3) {
    const end = Math.min(offset + CHUNK_3, bytes.length);
    const chunk = bytes.slice(offset, end);
    const decrypted = stream.process(chunk);
    b64 += u8ToBase64Chunk(decrypted, 0, decrypted.length);
    onProgress?.(0.4 + 0.5 * ((offset + (end - offset)) / bytes.length));
    await new Promise((r) => setImmediate(r));
  }

  onProgress?.(0.9);
  await FileSystem.writeAsStringAsync(outputPath, b64, { encoding: FileSystem.EncodingType.Base64 });
  onProgress?.(1);
}

export function getCoverUrl(urlTemplate: string, size = 2048): string {
  let url = urlTemplate.replace('{width}', String(size)).replace('{height}', String(size)).replace('{format}', 'jpg');
  if (url.includes(',')) {
    const parts = url.split('/');
    for (let i = 0; i < parts.length; i++) {
      if (parts[i].includes(',') && /\.(jpg|jpeg|png)/i.test(parts[i])) {
        parts[i] = parts[i].split(',')[0];
      }
    }
    url = parts.join('/');
  }
  url = url.replace(/\.(JPG|JPEG|PNG)/g, (m) => m.toLowerCase());
  return url;
}

export function mapSongToKkboxTrack(raw: any): import('../types').KkboxTrack {
  const coverTemplate = raw.album_photo_info?.url_template || '';
  const songMoreUrl: string = raw.song_more_url || '';
  let songId = '';
  if (songMoreUrl) {
    const parts = songMoreUrl.split('/');
    songId = parts[parts.length - 1] || '';
  }
  if (!songId) songId = raw.song_id != null ? String(raw.song_id) : '';
  const artist = raw.artist_name || raw.artist_role?.mainartists?.[0] || '';
  return {
    id: songId,
    encryptedId: raw.encrypted_song_id || '',
    title: raw.song_name || '',
    artist,
    cover: coverTemplate ? getCoverUrl(coverTemplate) : '',
    album: raw.album_name || '',
    albumId: raw.album_id != null ? String(raw.album_id) : '',
    albumArtist: artist,
    trackNumber: raw.song_idx || 0,
    releaseDate: raw.album_date || undefined,
    duration: Math.floor((raw.duration_ms || 0) / 1000),
    qualities: raw.audio_quality || [],
  };
}

export function mapAlbumToKkboxAlbum(raw: any, rawSongs: any[]): import('../types').KkboxAlbum {
  const album = raw.album || raw;
  const coverTemplate = album.album_photo_info?.url_template || '';
  const albumCover = coverTemplate ? getCoverUrl(coverTemplate) : '';
  const albumArtist = album.artist_name || '';
  const tracks = (rawSongs || []).map((s: any) => {
    const t = mapSongToKkboxTrack(s);
    if (!t.cover) t.cover = albumCover;
    if (!t.album) t.album = album.album_name || '';
    if (!t.artist) t.artist = albumArtist;
    return t;
  });
  return {
    id: String(album.album_id || ''),
    encryptedId: album.encrypted_album_id || '',
    title: album.album_name || '',
    artist: albumArtist,
    cover: albumCover,
    year: album.album_date || '',
    tracks,
  };
}

export function getAvailableQualities(song: any): string[] {
  const q = song.audio_quality || ['128k'];
  return q;
}

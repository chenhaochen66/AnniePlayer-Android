import type { Quality } from '../types';

const MUSIC_URL = 'http://8.148.232.102/music/';
const KEY = '8d3b41fe49279d1d41648f01fdc588ff';
const INDEX = [7, 15, 3, 21, 45, 36, 18, 27, 55, 14, 26, 39, 40, 8, 19, 48];

const md5 = require('js-md5') as (data: string) => string;
const CryptoJS = require('crypto-js');

const SERVER_MAP: Record<string, string> = { kg: 'kg', tx: 'qq', wy: 'wy', kw: 'kw' };
const QUALITY_MAP: Record<string, Record<string, string>> = {
  common: { '128k': 'standard', '320k': 'exhigh', flac: 'lossless' },
  kw: { '128k': '128kmp3', '320k': '320kmp3', flac: '2000kflac' },
};

function getLevel(server: string, quality: string): string {
  if (server === 'kw') return QUALITY_MAP.kw[quality] || '128kmp3';
  return QUALITY_MAP.common[quality] || 'standard';
}

// 从 SHA256 hex (64字符) 提取 sign (16字符)
function extractSign(aHex: string): string {
  return INDEX.map((i) => aHex[i]).join('');
}

// C[0] = 0x67; C[i+1] = data[i] ^ (ord(sign[i % 16]) ^ 0x67); 返回小写 hex
function xorEncrypt(data: string, sign: string): string {
  const bytes: number[] = [0x67];
  for (let i = 0; i < data.length; i++) {
    bytes.push(data.charCodeAt(i) ^ (sign.charCodeAt(i % 16) ^ 0x67));
  }
  return bytes.map((b) => b.toString(16).padStart(2, '0')).join('');
}

// 苏澜加密: Ro = 'v2' + G + E + sign + F
function encryptRo(query: string): string {
  const mid = query.match(/id=([^&]+)/)?.[1] ?? '';
  const mtime = query.match(/time=([^&]+)/)?.[1] ?? '';

  // A = SHA256(id + time + key) -> sign
  const A = CryptoJS.SHA256(mid + mtime + KEY).toString();
  const sign = extractSign(A);

  // E = xorEncrypt(query + '&sign=' + sign)
  const E = xorEncrypt(query + '&sign=' + sign, sign);

  // G = '0' + 毫秒时间戳 (14 字符)
  const G = ('0' + String(Date.now())).slice(0, 14);

  // F = MD5(E + sign + KEY)
  const F = md5(E + sign + KEY);

  return 'v2' + G + E + sign + F;
}

export async function fetchAnnieMusicUrl(source: string, id: string, quality: Quality): Promise<string> {
  const server = SERVER_MAP[source] || source;
  const level = getLevel(server, quality);
  const query = `time=${Date.now()}&server=${server}&id=${id}&level=${level}`;
  const ro = encryptRo(query);

  const resp = await fetch(`${MUSIC_URL}?Ro=${encodeURIComponent(ro)}`, {
    headers: { 'User-Agent': 'Sulune/10.99-release' },
  });
  const body = await resp.json();
  if (body.code === 200 && body.data?.url) return body.data.url;
  throw new Error(body.msg || body.tip || '苏澜音源获取失败');
}

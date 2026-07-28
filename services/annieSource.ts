import type { Quality } from '../types';
import aesjs from 'aes-js';

const ENCRYPT_URL = 'https://yy.mxpy.cn/api/encrypt';
const MUSIC_URL = 'http://8.148.232.102/music/';
const AES_KEY = 'xtmpi6jzGrzQ1E0w';
const SALT = '2e39f9eedb53e1f1ed83161e6e48716c';
const FIXED_TIMESTAMP = '1784744546107';

const SERVER_MAP: Record<string, string> = { kg: 'kg', tx: 'qq', wy: 'wy', kw: 'kw' };
const QUALITY_MAP: Record<string, Record<string, string>> = {
  common: { '128k': 'standard', '320k': 'exhigh', flac: 'lossless' },
  kw: { '128k': '128kmp3', '320k': '320kmp3', flac: '2000kflac' },
};

function getLevel(server: string, quality: string): string {
  if (server === 'kw') return QUALITY_MAP.kw[quality] || '128kmp3';
  return QUALITY_MAP.common[quality] || 'standard';
}

function aesEncrypt(plaintext: string): string {
  const key = Array.from(AES_KEY).map((c) => c.charCodeAt(0));
  const textBytes = Array.from(plaintext).map((c) => c.charCodeAt(0));

  // PKCS7 padding
  const padding = 16 - (textBytes.length % 16);
  const padded = new Uint8Array(textBytes.length + padding);
  padded.set(textBytes);
  for (let i = textBytes.length; i < padded.length; i++) padded[i] = padding;

  // AES-128-ECB
  const ecb = new aesjs.ModeOfOperation.ecb(key);
  const encrypted = ecb.encrypt(padded);

  // Base64
  const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  let b64 = '';
  for (let i = 0; i < encrypted.length; i += 3) {
    const n = (encrypted[i] << 16) | ((i + 1 < encrypted.length ? encrypted[i + 1] : 0) << 8) | (i + 2 < encrypted.length ? encrypted[i + 2] : 0);
    b64 += chars[(n >> 18) & 63] + chars[(n >> 12) & 63];
    b64 += i + 1 < encrypted.length ? chars[(n >> 6) & 63] : '=';
    b64 += i + 2 < encrypted.length ? chars[n & 63] : '=';
  }
  return b64;
}

export async function fetchAnnieMusicUrl(source: string, id: string, quality: Quality): Promise<string> {
  const server = SERVER_MAP[source] || source;
  const level = getLevel(server, quality);
  const plaintext = `time=${FIXED_TIMESTAMP}&server=${server}&id=${id}&level=${level}`;
  const inputData = aesEncrypt(plaintext);

  const resp1 = await fetch(ENCRYPT_URL, {
    method: 'POST',
    headers: { 'User-Agent': 'okhttp/4.12.0', 'Content-Type': 'application/json' },
    body: JSON.stringify({ input: inputData, salt: SALT }),
  });
  const body1 = await resp1.json();
  const sign = body1.sign;
  if (!sign) throw new Error(`获取sign失败: ${JSON.stringify(body1)}`);

  const resp2 = await fetch(`${MUSIC_URL}?Ro=${sign}`, {
    headers: { 'User-Agent': 'Roche/6.9-release' },
  });
  const body2 = await resp2.json();
  if (body2.code === 200 && body2.data?.url) return body2.data.url;
  throw new Error(body2.msg || body2.tip || '安妮音源获取失败');
}

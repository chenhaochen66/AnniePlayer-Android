// ── QQ 3DES lyrics decryption ──
import pako from 'pako';

const IP_RULE = [
  34,42,50,58,2,10,18,26,36,44,52,60,4,12,20,28,
  38,46,54,62,6,14,22,30,40,48,56,64,8,16,24,32,
  33,41,49,57,1,9,17,25,35,43,51,59,3,11,19,27,
  37,45,53,61,5,13,21,29,39,47,55,63,7,15,23,31,
];
const INV_IP_RULE = [
  37,5,45,13,53,21,61,29,38,6,46,14,54,22,62,30,
  39,7,47,15,55,23,63,31,40,8,48,16,56,24,64,32,
  33,1,41,9,49,17,57,25,34,2,42,10,50,18,58,26,
  35,3,43,11,51,19,59,27,36,4,44,12,52,20,60,28,
];
const E_BOX = [
  32,1,2,3,4,5,4,5,6,7,8,9,8,9,10,11,12,13,12,13,14,15,16,17,
  16,17,18,19,20,21,20,21,22,23,24,25,24,25,26,27,28,29,28,29,30,31,32,1,
];
const P_BOX = [
  16,7,20,21,29,12,28,17,1,15,23,26,5,18,31,10,
  2,8,24,14,32,27,3,9,19,13,30,6,22,11,4,25,
];
const SBOXES = [
  [14,4,13,1,2,15,11,8,3,10,6,12,5,9,0,7,0,15,7,4,14,2,13,1,10,6,12,11,9,5,3,8,4,1,14,8,13,6,2,11,15,12,9,7,3,10,5,0,15,12,8,2,4,9,1,7,5,11,3,14,10,0,6,13],
  [15,1,8,14,6,11,3,4,9,7,2,13,12,0,5,10,3,13,4,7,15,2,8,15,12,0,1,10,6,9,11,5,0,14,7,11,10,4,13,1,5,8,12,6,9,3,2,15,13,8,10,1,3,15,4,2,11,6,7,12,0,5,14,9],
  [10,0,9,14,6,3,15,5,1,13,12,7,11,4,2,8,13,7,0,9,3,4,6,10,2,8,5,14,12,11,15,1,13,6,4,9,8,15,3,0,11,1,2,12,5,10,14,7,1,10,13,0,6,9,8,7,4,15,14,3,11,5,2,12],
  [7,13,14,3,0,6,9,10,1,2,8,5,11,12,4,15,13,8,11,5,6,15,0,3,4,7,2,12,1,10,14,9,10,6,9,0,12,11,7,13,15,1,3,14,5,2,8,4,3,15,0,6,10,10,13,8,9,4,5,11,12,7,2,14],
  [2,12,4,1,7,10,11,6,8,5,3,15,13,0,14,9,14,11,2,12,4,7,13,1,5,0,15,10,3,9,8,6,4,2,1,11,10,13,7,8,15,9,12,5,6,3,0,14,11,8,12,7,1,14,2,13,6,15,0,9,10,4,5,3],
  [12,1,10,15,9,2,6,8,0,13,3,4,14,7,5,11,10,15,4,2,7,12,9,5,6,1,13,14,0,11,3,8,9,14,15,5,2,8,12,3,7,0,4,10,1,13,11,6,4,3,2,12,9,5,15,10,11,14,1,7,6,0,8,13],
  [4,11,2,14,15,0,8,13,3,12,9,7,5,10,6,1,13,0,11,7,4,9,1,10,14,3,5,12,2,15,8,6,1,4,11,13,12,3,7,14,10,15,6,8,0,5,9,2,6,11,13,8,1,4,10,7,9,5,0,15,14,2,3,12],
  [13,2,8,4,6,15,11,1,10,9,3,14,5,0,12,7,1,15,13,8,10,3,7,4,12,5,6,11,0,14,9,2,7,11,4,1,9,12,14,2,0,6,10,13,15,3,5,8,2,1,14,7,4,10,8,13,15,12,9,0,3,5,6,11],
];
const KEY_PERM_C = [56,48,40,32,24,16,8,0,57,49,41,33,25,17,9,1,58,50,42,34,26,18,10,2,59,51,43,35];
const KEY_PERM_D = [62,54,46,38,30,22,14,6,61,53,45,37,29,21,13,5,60,52,44,36,28,20,12,4,27,19,11,3];
const KEY_COMPRESSION = [13,16,10,23,0,4,2,27,14,5,20,9,22,18,11,3,25,7,15,6,26,19,12,1,40,51,30,36,46,54,29,39,50,44,32,47,43,48,38,55,33,52,45,41,49,35,28,31];
const KEY_SHIFT = [1,1,2,2,2,2,2,2,1,2,2,2,2,2,2,1];
const KEY1 = [0x21,0x40,0x23,0x29,0x28,0x2a,0x24,0x25];
const KEY2 = [0x31,0x32,0x33,0x5a,0x58,0x43,0x21,0x40];
const KEY3 = [0x21,0x40,0x23,0x29,0x28,0x4e,0x48,0x4c];

function sboxIdx(a: number): number { return (a & 0x20) | ((a & 0x1f) >> 1) | ((a & 1) << 4); }
function permKey(key: number[], table: number[]): number {
  let r = 0;
  const k = ((key[0]|key[1]<<8|key[2]<<16|key[3]<<24) >>> 0) * 0x100000000 + ((key[4]|key[5]<<8|key[6]<<16|key[7]<<24) >>> 0);
  for (let i = 0; i < table.length; i++) r |= ((k >> (63 - table[i])) & 1) << (table.length - 1 - i);
  return r;
}
function rot28(v: number, a: number): number { return ((v << a) | (v >>> (28 - a))) & 0xFFFFFFF0; }
function genSubkeys(key: number[], enc: boolean): number[][] {
  let c = permKey(key, KEY_PERM_C) << 4, d = permKey(key, KEY_PERM_D) << 4;
  const sk: number[][] = Array.from({ length: 16 }, () => [0,0,0,0,0,0]);
  for (let i = 0; i < 16; i++) {
    c = rot28(c, KEY_SHIFT[i]); d = rot28(d, KEY_SHIFT[i]);
    const ri = enc ? i : 15 - i;
    let sub = 0;
    for (let k = 0; k < 48; k++) {
      const pos = KEY_COMPRESSION[k];
      if ((pos < 28 ? (c >>> (31 - pos)) : (d >>> (31 - (pos - 27)))) & 1) sub |= 1 << (47 - k);
    }
    sk[ri] = [((sub >>> 40) & 0xff), ((sub >>> 32) & 0xff), ((sub >>> 24) & 0xff), ((sub >>> 16) & 0xff), ((sub >>> 8) & 0xff), (sub & 0xff)];
  }
  return sk;
}
function expandE(x: number): number {
  let r = 0;
  for (let i = 0; i < 48; i++) r |= ((x >>> (32 - E_BOX[i])) & 1) << (47 - i);
  return r;
}
function fFunc(state: number, sk: number[]): number {
  const skn = (sk[0]<<40)|(sk[1]<<32)|(sk[2]<<24)|(sk[3]<<16)|(sk[4]<<8)|sk[5];
  const xored = expandE(state) ^ (skn >>> 0);
  let r = 0;
  for (let i = 0; i < 8; i++) r |= SBOXES[i][sboxIdx((xored >>> (42 - i * 6)) & 0x3F)] << (28 - i * 4);
  let p = 0;
  for (let i = 0; i < 32; i++) p |= ((r >>> (32 - P_BOX[i])) & 1) << (31 - i);
  return p;
}
function desBlock(block: Uint8Array, sk: number[][]): Uint8Array {
  let L = 0, R = 0;
  const norm = ((block[0]<<56)|(block[1]<<48)|(block[2]<<40)|(block[3]<<32)|(block[4]<<24)|(block[5]<<16)|(block[6]<<8)|block[7]) >>> 0;
  for (let i = 0; i < 64; i++) {
    const bit = (norm >>> (63 - (IP_RULE[i] - 1))) & 1;
    if (i < 32) L |= bit << (31 - i); else R |= bit << (63 - i);
  }
  for (let i = 0; i < 15; i++) { const t = L ^ fFunc(R, sk[i]); L = R; R = t; }
  L ^= fFunc(R, sk[15]);
  const n = ((L << 32) | R) >>> 0;
  let out = 0;
  for (let i = 0; i < 64; i++) out |= ((n >>> (63 - (INV_IP_RULE[i] - 1))) & 1) << (63 - i);
  return new Uint8Array([(out>>56)&0xff,(out>>48)&0xff,(out>>40)&0xff,(out>>32)&0xff,(out>>24)&0xff,(out>>16)&0xff,(out>>8)&0xff,out&0xff]);
}
function tripleDesDecrypt(data: Uint8Array): Uint8Array {
  const s1 = genSubkeys(KEY1, false), s2 = genSubkeys(KEY2, true), s3 = genSubkeys(KEY3, false);
  const out = new Uint8Array(data.length);
  for (let i = 0; i < data.length; i += 8) {
    let tmp = desBlock(data.slice(i, i+8), s1);
    tmp = desBlock(tmp, s2);
    out.set(desBlock(tmp, s3), i);
  }
  return out;
}

export function decryptQQLyric(hex: string): string {
  const data = new Uint8Array(hex.match(/.{1,2}/g)!.map((b) => parseInt(b, 16)));
  const dec = tripleDesDecrypt(data);
  const raw = pako.inflate(dec);
  let str = '';
  for (let i = 0; i < raw.length; i++) str += String.fromCharCode(raw[i]);
  return str.replace(/^\ufeff/, '');
}

import * as FileSystem from 'expo-file-system/legacy';

const DBG = '[meta]';

export async function fetchImageBytes(url: string): Promise<Uint8Array | null> {
  if (!url) return null;
  try {
    const ext = (url.split('.').pop()?.split('?')[0] || 'jpg');
    const tmp = `${FileSystem.cacheDirectory}cover_${Date.now()}.${ext}`;
    const r = await FileSystem.downloadAsync(url, tmp);
    const b64 = await FileSystem.readAsStringAsync(r.uri, { encoding: FileSystem.EncodingType.Base64 });
    await FileSystem.deleteAsync(tmp, { idempotent: true });
    const u8 = Uint8Array.from(atob(b64), c => c.charCodeAt(0));
    console.log(DBG, 'cover bytes:', u8.length);
    return u8;
  } catch { return null; }
}

function encUtf8(s: string): Uint8Array {
  const b: number[] = [];
  for (let i = 0; i < s.length; i++) {
    let c = s.charCodeAt(i);
    if (c < 0x80) b.push(c);
    else if (c < 0x800) { b.push(0xC0 | (c >> 6)); b.push(0x80 | (c & 0x3F)); }
    else if (c < 0xD800 || c >= 0xE000) { b.push(0xE0 | (c >> 12)); b.push(0x80 | ((c >> 6) & 0x3F)); b.push(0x80 | (c & 0x3F)); }
    else { i++; const c2 = s.charCodeAt(i); c = ((c & 0x3FF) << 10) + (c2 & 0x3FF) + 0x10000; b.push(0xF0 | (c >> 18)); b.push(0x80 | ((c >> 12) & 0x3F)); b.push(0x80 | ((c >> 6) & 0x3F)); b.push(0x80 | (c & 0x3F)); }
  }
  return new Uint8Array(b);
}

function u32BE(v: number): Uint8Array {
  return new Uint8Array([(v >> 24) & 0xFF, (v >> 16) & 0xFF, (v >> 8) & 0xFF, v & 0xFF]);
}

function u32LE(v: number): Uint8Array {
  return new Uint8Array([v & 0xFF, (v >> 8) & 0xFF, (v >> 16) & 0xFF, (v >> 24) & 0xFF]);
}
function flacBlock(type: number, data: Uint8Array, isLast: boolean): Uint8Array {
  const h = new Uint8Array(4);
  h[0] = (isLast ? 0x80 : 0) | (type & 0x7F);
  h[1] = (data.length >> 16) & 0xFF;
  h[2] = (data.length >> 8) & 0xFF;
  h[3] = data.length & 0xFF;
  const r = new Uint8Array(4 + data.length);
  r.set(h); r.set(data, 4);
  return r;
}

export function appendFlacMetadataToBytes(audioBytes: Uint8Array, metadata: {
  title: string; artist: string; album?: string; lyric: string; coverUrl: string; imageBytes: Uint8Array | null;
}): Uint8Array {
  console.log(DBG, 'start, input:', audioBytes.length);
  if (audioBytes.length < 42) return audioBytes;

  // Build VORBIS_COMMENT data
  const vFields: string[] = [`TITLE=${metadata.title}`, `ARTIST=${metadata.artist}`];
  if (metadata.album) vFields.push(`ALBUM=${metadata.album}`);
  if (metadata.lyric) vFields.push(`LYRICS=${metadata.lyric}`);

  const vendor = encUtf8('reference libFLAC 1.4.3');
  const ndata = vFields.reduce((s, f) => { const fb = encUtf8(f); return s + 4 + fb.length; }, 0);
  const vData = new Uint8Array(4 + vendor.length + 4 + ndata);
  let vp = 0;
  vData.set(u32LE(vendor.length), vp); vp += 4;
  vData.set(vendor, vp); vp += vendor.length;
  vData.set(u32LE(vFields.length), vp); vp += 4;
  for (const f of vFields) {
    const fb = encUtf8(f);
    vData.set(u32LE(fb.length), vp); vp += 4;
    vData.set(fb, vp); vp += fb.length;
  }

  const hasPic = !!metadata.imageBytes;
  const vBlock = flacBlock(4, vData, !hasPic);

  // Build PICTURE block
  let picBlock: Uint8Array | null = null;
  if (hasPic && metadata.imageBytes) {
    const ib = metadata.imageBytes;
    let iw = 0, ih = 0;
    if (ib.length > 10 && ib[0] === 0xFF && ib[1] === 0xD8) {
      for (let j = 2; j < ib.length - 8;) {
        if (ib[j] !== 0xFF) { j++; continue; }
        const m = ib[j + 1];
        if ((m >= 0xC0 && m <= 0xC3)) { iw = (ib[j + 7] << 8) | ib[j + 8]; ih = (ib[j + 5] << 8) | ib[j + 6]; break; }
        if (m === 0xD9 || m === 0xDA) break;
        const sl = (ib[j + 2] << 8) | ib[j + 3];
        if (sl < 2) break;
        j += sl + 2;
      }
    }
    const mime = metadata.coverUrl?.includes('png') ? encUtf8('image/png') : encUtf8('image/jpeg');
    const desc = encUtf8('');
    const pd = new Uint8Array(4 + 4 + mime.length + 4 + desc.length + 4 + 4 + 4 + 4 + 4 + ib.length);
    let pp = 0;
    pd.set(u32BE(3), pp); pp += 4;
    pd.set(u32BE(mime.length), pp); pp += 4;
    pd.set(mime, pp); pp += mime.length;
    pd.set(u32BE(desc.length), pp); pp += 4;
    pd.set(desc, pp); pp += desc.length;
    pd.set(u32BE(iw), pp); pp += 4;
    pd.set(u32BE(ih), pp); pp += 4;
    pd.set(u32BE(24), pp); pp += 4;
    pd.set(u32BE(0), pp); pp += 4;
    pd.set(u32BE(ib.length), pp); pp += 4;
    pd.set(ib, pp);
    picBlock = flacBlock(6, pd, true);
  }

  // Find audio data start in original file
  let audioOff = 42;
  if ((audioBytes[4] & 0x80) === 0) {
    while (audioOff < audioBytes.length) {
      if (audioOff + 4 > audioBytes.length) break;
      const isLast = (audioBytes[audioOff] & 0x80) !== 0;
      const bl = ((audioBytes[audioOff + 1] << 16) | (audioBytes[audioOff + 2] << 8) | audioBytes[audioOff + 3]) + 4;
      console.log(DBG, 'block at', audioOff, 'type:', audioBytes[audioOff] & 0x7F, 'len:', bl, 'isLast:', isLast);
      audioOff += bl;
      if (isLast) break;
    }
  }
  console.log(DBG, 'audio start at:', audioOff);

  // Assemble new metadata blocks
  const meta = picBlock ? (() => {
    const m = new Uint8Array(vBlock.length + picBlock.length);
    m.set(vBlock); m.set(picBlock, vBlock.length);
    return m;
  })() : vBlock;

  // Build final file
  const out = new Uint8Array(42 + meta.length + audioBytes.length - audioOff);
  out.set(audioBytes.subarray(0, 42));
  out[4] &= 0x7F;
  out.set(meta, 42);
  out.set(audioBytes.subarray(audioOff), 42 + meta.length);
  console.log(DBG, 'output:', out.length);
  console.log(DBG, 'first 4 bytes:', Array.from(out.slice(0,4)).map(b => b.toString(16)).join(' '));
  console.log(DBG, 'vorbis fields:', vFields.length, 'vendor:', vendor.length, 'hasPic:', hasPic);
  return out;
}

// Keep MP3 functions for downloadService compatibility
function base64ToBytes(b64: string): Uint8Array {
  return Uint8Array.from(atob(b64.replace(/[\r\n]/g, '')), c => c.charCodeAt(0));
}
function bytesToBase64(bytes: Uint8Array): string {
  let s = '';
  for (let i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
  return btoa(s);
}
function findMP3DataStart(bytes: Uint8Array): number {
  for (let i = 0; i < Math.min(bytes.length, 4096); i++) {
    if (bytes[i] === 0xFF && (bytes[i + 1] & 0xE0) === 0xE0) return i;
  }
  return 0;
}
function createID3Frame(frameId: string, data: Uint8Array): Uint8Array {
  const h = new TextEncoder().encode(frameId);
  const sz = new Uint8Array(4);
  sz[0] = (data.length >> 24) & 0xFF; sz[1] = (data.length >> 16) & 0xFF;
  sz[2] = (data.length >> 8) & 0xFF; sz[3] = data.length & 0xFF;
  const r = new Uint8Array(h.length + sz.length + 2 + data.length);
  r.set(h); r.set(sz, h.length); r[8] = 0; r[9] = 0; r.set(data, 10);
  return r;
}
function encUtf16(s: string): Uint8Array {
  const bom = new Uint8Array([0xFF, 0xFE]);
  const u: number[] = [];
  for (let i = 0; i < s.length; i++) {
    let c = s.charCodeAt(i);
    if (c < 0xD800 || c >= 0xE000) { u.push(c & 0xFF); u.push((c >> 8) & 0xFF); }
    else { i++; const c2 = s.charCodeAt(i); c = ((c & 0x3FF) << 10) + (c2 & 0x3FF) + 0x10000; const hi = Math.floor((c - 0x10000) / 0x400) + 0xD800; const lo = ((c - 0x10000) % 0x400) + 0xDC00; u.push(hi & 0xFF); u.push((hi >> 8) & 0xFF); u.push(lo & 0xFF); u.push((lo >> 8) & 0xFF); }
  }
  const nt = new Uint8Array([0x00, 0x00]);
  const r = new Uint8Array(bom.length + u.length + nt.length);
  r.set(bom); r.set(u, bom.length); r.set(nt, bom.length + u.length);
  return r;
}
function encStr(s: string): { data: Uint8Array; eb: number } {
  return { data: encUtf16(s), eb: 1 };
}
function txtFrame(id: string, text: string): Uint8Array | null {
  if (!text) return null;
  const { data, eb } = encStr(text);
  const fd = new Uint8Array(1 + data.length);
  fd[0] = eb; fd.set(data, 1);
  return createID3Frame(id, fd);
}
function apicFrame(url: string, img: Uint8Array | null): Uint8Array | null {
  if (!img) return null;
  const mt = url?.includes('png') ? 'image/png' : 'image/jpeg';
  const mb = new TextEncoder().encode(mt);
  const d = new TextEncoder().encode('');
  const fd = new Uint8Array(1 + mb.length + 1 + 1 + 1 + d.length + img.length);
  fd[0] = 0; fd.set(mb, 1); fd[1 + mb.length] = 0; fd[2 + mb.length] = 3; fd[3 + mb.length] = 0;
  fd.set(d, 4 + mb.length); fd.set(img, 4 + mb.length + d.length);
  return createID3Frame('APIC', fd);
}
function usltFrame(text: string): Uint8Array | null {
  if (!text) return null;
  const { data, eb } = encStr(text);
  const lang = new TextEncoder().encode('chi');
  const { data: dd } = encStr('');
  const fd = new Uint8Array(1 + lang.length + dd.length + data.length);
  fd[0] = eb; fd.set(lang, 1); fd.set(dd, 4); fd.set(data, 4 + dd.length);
  return createID3Frame('USLT', fd);
}
function buildID3v2Header(frames: Uint8Array[]): Uint8Array {
  const total = frames.reduce((s, f) => s + f.length, 0);
  const h = new Uint8Array(10);
  h.set([0x49, 0x44, 0x33]); h[3] = 3; h[4] = 0;
  const sz = total;
  h[6] = (sz >> 21) & 0x7F; h[7] = (sz >> 14) & 0x7F;
  h[8] = (sz >> 7) & 0x7F; h[9] = sz & 0x7F;
  const r = new Uint8Array(h.length + total);
  r.set(h); let off = h.length;
  for (const f of frames) { r.set(f, off); off += f.length; }
  return r;
}

export function writeId3ToBytes(audioBytes: Uint8Array, metadata: {
  title: string; artist: string; album?: string; lyric: string; coverUrl: string; imageBytes: Uint8Array | null;
}): Uint8Array {
  const audioStart = findMP3DataStart(audioBytes);
  const frames: Uint8Array[] = [];
  for (const f of [txtFrame('TIT2', metadata.title), txtFrame('TPE1', metadata.artist),
    metadata.album ? txtFrame('TALB', metadata.album) : null,
    apicFrame(metadata.coverUrl, metadata.imageBytes), usltFrame(metadata.lyric)]) { if (f) frames.push(f); }
  if (frames.length === 0) return audioBytes;
  const id3v2 = buildID3v2Header(frames);
  const audioData = audioBytes.slice(audioStart);
  const out = new Uint8Array(id3v2.length + audioData.length);
  out.set(id3v2);
  for (let i = 0; i < audioData.length; i++) out[id3v2.length + i] = audioData[i];
  return out;
}

export function writeId3ToBase64(audioBase64: string, metadata: {
  title: string; artist: string; album?: string; lyric: string; coverUrl: string; imageBytes: Uint8Array | null;
}): string {
  return bytesToBase64(writeId3ToBytes(base64ToBytes(audioBase64), metadata));
}

export function appendFlacMetadata(audioBase64: string, metadata: {
  title: string; artist: string; album?: string; lyric: string; coverUrl: string; imageBytes: Uint8Array | null;
}): string {
  return bytesToBase64(appendFlacMetadataToBytes(base64ToBytes(audioBase64), metadata));
}

import type { Quality, Song } from '../../types';
import { decodeHtmlEntities } from '../../utils/stringUtils';

const QQ_HEADERS = {
  'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
  'Referer': 'https://y.qq.com/',
};

// ── 歌单 ──

export async function getQQPlaylist(disstid: string): Promise<{
  name: string; creator: string; cover: string; songs: Song[];
} | null> {
  try {
    const allSongs: Song[] = [];
    let name = '', creator = '', cover = '';
    let total = 0;
    let begin = 0;
    const PAGE_SIZE = 200;

    while (true) {
      const url = `https://c.y.qq.com/v8/fcg-bin/fcg_v8_playlist_cp.fcg?id=${disstid}&format=json&song_begin=${begin}&song_num=${PAGE_SIZE}&loginUin=0&hostUin=0&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0`;
      const resp = await fetch(url, { headers: QQ_HEADERS });
      const text = await resp.text();
      const data = JSON.parse(text.replace(/^\xef\xbb\xbf/, ''));
      if (data.code !== 0) break;
      const cd = data.data?.cdlist?.[0];
      if (!cd) break;

      if (begin === 0) {
        name = cd.dissname || '';
        creator = cd.nickname || '';
        cover = cd.logo || '';
        total = cd.cur_song_num || 0;
      }

      const list = cd.songlist || [];
      for (const s of list) {
        const singer = s.singer?.[0]?.name || '';
        const qual: Quality[] = [];
        if (s.sizeflac) qual.push('flac');
        if (s.size320) qual.push('320k');
        if (s.size128) qual.push('128k');
        if (qual.length === 0) qual.push('128k');

        const albumMid = s.albummid || '';
        const coverUrl = albumMid ? `https://y.gtimg.cn/music/photo_new/T002R800x800M000${albumMid}.jpg` : '';
        allSongs.push({
          id: `qq:${s.songmid || ''}`,
          title: decodeHtmlEntities(s.songname || '未知歌曲'),
          artist: decodeHtmlEntities(singer),
          cover: coverUrl,
          album: decodeHtmlEntities(s.albumname || ''),
          albumId: s.albummid || undefined,
          duration: s.interval || 0,
          platform: 'qq',
          playUrl: '',
          lyric: undefined,
          tlyric: undefined,
          qualities: qual,
        });
      }

      if (begin + PAGE_SIZE >= total) break;
      begin += PAGE_SIZE;
    }

    if (allSongs.length === 0) return null;
    return { name, creator, cover, songs: allSongs };
  } catch {
    return null;
  }
}

// ── 歌词 (简化版：通过第三方 API 获取) ──

export async function fetchQQLyric(songId: string): Promise<{ lrc: string; tlyric: string | null }> {
  const songMid = songId.replace(/^qq:/, '');
  try {
    const payload = {
      request: {
        module: 'music.musichallSong.PlayLyricInfo',
        method: 'GetPlayLyricInfo',
        param: { songMID: songMid, crypt: 0, qrc: 0, trans: 1 },
      },
    };
    const resp = await fetch('https://u.y.qq.com/cgi-bin/musicu.fcg', {
      method: 'POST',
      headers: {
        'User-Agent': 'okhttp/3.14.9',
        'Content-Type': 'application/json; charset=utf-8',
        'Cookie': 'tmeLoginType=-1;',
      },
      body: JSON.stringify(payload),
    });
    const data = await resp.json();
    const d = data?.request?.data;
    if (!d?.lyric) return { lrc: '[00:00.00]暂无歌词', tlyric: null };

    try {
      const lrcB64 = typeof d.lyric === 'string' ? d.lyric : '';
      const lrc = decodeURIComponent(escape(atob(lrcB64)));
      let tlyric: string | null = null;
      if (d.trans) {
        const transB64 = typeof d.trans === 'string' ? d.trans : '';
        const trans = decodeURIComponent(escape(atob(transB64)));
        tlyric = trans;
      }
      return { lrc, tlyric };
    } catch {
      return { lrc: '[00:00.00]暂无歌词', tlyric: null };
    }
  } catch {
    return { lrc: '[00:00.00]暂无歌词', tlyric: null };
  }
}

// ── 单曲详情（补音轨号/碟号/发行日期） ──

export async function enrichQQSong(songId: string): Promise<{ trackNumber?: string; discNumber?: string; releaseDate?: string }> {
  const songmid = songId.replace(/^qq:/, '');
  try {
    const url = `https://c.y.qq.com/v8/fcg-bin/fcg_play_single_song.fcg?songmid=${songmid}&tpl=yqq_song_detail&format=json&loginUin=0&hostUin=0&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0`;
    const resp = await fetch(url, { headers: QQ_HEADERS });
    const text = await resp.text();
    const data = JSON.parse(text.replace(/^\xef\xbb\xbf/, ''));
    if (data.code !== 0 || !data.data?.[0]) return {};
    const s = data.data[0];
    return {
      trackNumber: s.index_album ? String(s.index_album) : undefined,
      discNumber: s.index_cd ? String(s.index_cd) : undefined,
      releaseDate: s.time_public || undefined,
    };
  } catch {
    return {};
  }
}

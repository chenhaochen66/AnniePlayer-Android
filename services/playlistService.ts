import type { Song } from '../types';
import { getKuwoPlaylist } from './platforms/kuwoApi';
import { getNeteasePlaylist } from './platforms/neteaseApi';
import { getQQPlaylist } from './platforms/qqApi';
import { getKugouPlaylist } from './platforms/kugouApi';

export interface PlaylistResult {
  name: string;
  creator: string;
  cover: string;
  platform: string;
  songs: Song[];
}

export function parsePlaylistUrl(text: string): { platform: string; id: string } | null {
  // Extract URL from mixed text (e.g. "分享歌单: xxx https://...")
  const urlMatch = text.trim().match(/https?:\/\/[^\s]+/);
  const raw = (urlMatch ? urlMatch[0] : text.trim()).split('?')[0]; // strip query for path matching
  const full = urlMatch ? urlMatch[0] : text.trim();

  // Kuwo: kuwo.cn/.../playlist_detail/数字
  let m = raw.match(/kuwo\.cn\/.*?playlist_detail\/(\d+)/);
  if (m) return { platform: 'kuwo', id: m[1] };

  // NetEase: music.163.com/.../playlist?id=数字
  m = full.match(/music\.163\.com\/.*[?&]id=(\d+)/);
  if (m) return { platform: 'netease', id: m[1] };

  // QQ: y.qq.com 域名下 id=数字 (支持 i2/sub/ 等子域名)
  m = full.match(/[^.]*\.?y\.qq\.com\/.*[?&]id=(\d+)/);
  if (m) return { platform: 'qq', id: m[1] };
  m = raw.match(/[^.]*\.?y\.qq\.com\/.*\/playlist\/(\d+)/);
  if (m) return { platform: 'qq', id: m[1] };

  // KuGou: kugou.com 含 gcid_
  m = full.match(/kugou\.com\/.*gcid_([a-z0-9]+)/i);
  if (m) return { platform: 'kugou', id: `gcid_${m[1]}` };
  m = raw.match(/kugou\.com\/(yy\/)?special\/single\/(\d+)/);
  if (m) return { platform: 'kugou', id: m[2] };

  // Plain ID detection
  const clean = text.trim();
  if (/^\d+$/.test(clean)) return { platform: 'netease', id: clean };
  if (clean.toLowerCase().includes('gcid_')) {
    const g = clean.match(/gcid_([a-z0-9]+)/i)?.[1] || clean;
    return { platform: 'kugou', id: `gcid_${g}` };
  }

  return null;
}

export async function fetchPlaylist(platform: string, id: string): Promise<PlaylistResult | null> {
  switch (platform) {
    case 'kuwo': {
      const result = await getKuwoPlaylist(id);
      if (!result) return null;
      return { ...result, platform: 'kuwo' };
    }
    case 'netease': {
      const result = await getNeteasePlaylist(id);
      if (!result) return null;
      return { ...result, platform: 'netease' };
    }
    case 'qq': {
      const result = await getQQPlaylist(id);
      if (!result) return null;
      return { ...result, platform: 'qq' };
    }
    case 'kugou': {
      const result = await getKugouPlaylist(id);
      if (!result) return null;
      return { ...result, platform: 'kugou' };
    }
    default:
      return null;
  }
}

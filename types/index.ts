export type Platform = 'netease' | 'kuwo' | 'kkbox' | 'qq' | 'kugou';

export type Quality = '128k' | '320k' | 'flac' | 'hires';

export const QUALITY_LABELS: Record<Quality, string> = {
  '128k': '标准音质',
  '320k': '高品音质',
  'flac': '无损音质',
  'hires': 'HiRes',
};

export const QUALITY_FORMATS: Record<Quality, string> = {
  '128k': '128kmp3',
  '320k': '320kmp3',
  'flac': '2000kflac',
  'hires': '4000kflac',
};

export interface Song {
  id: string;
  title: string;
  artist: string;
  cover: string;
  album?: string;
  albumId?: string;
  duration: number;
  platform: Platform;
  playUrl: string;
  lyric: string | undefined;
  tlyric: string | undefined;
  qualities: Quality[];
  albumArtist?: string;
  trackNumber?: string;
  discNumber?: string;
  releaseDate?: string;
}

export interface Playlist {
  id: string;
  name: string;
  creator?: string;
  cover: string;
  platform: Platform;
  songs: Song[];
  songCount: number;
}

export type LoopMode = 'sequential' | 'single' | 'shuffle';

export interface DownloadTask {
  songId: string;
  title: string;
  artist: string;
  cover: string;
  quality: Quality;
  progress: number;
  status: 'pending' | 'downloading' | 'completed' | 'error' | 'paused' | 'cancelled';
  filePath?: string;
}

export interface ColorPalette {
  primary: string;
  secondary: string;
  background: string;
  surface: string;
}

export type SearchResult = Song[];

export interface Album {
  albumid: string;
  name: string;
  artist: string;
  musiccnt: string;
  pub: string;
  pic: string;
  img: string;
  hts_img: string;
  company: string;
  lang: string;
  vip: string;
  PLAYCNT: string;
}

export interface AlbumSong {
  id: string;
  name: string;
  artist_name: string;
  duration: number;
  track?: number;
}

export interface Artist {
  id: string;
  name: string;
  picture: string;
  musicNum: number;
}

export interface QobuzTrack {
  id: string;
  title: string;
  artist: string;
  cover: string;
  album: string;
  albumId: string;
  albumArtist?: string;
  trackNumber: number;
  discNumber?: number;
  duration: number;
  streamable: boolean;
  label: string;
  year: number;
}

export interface QobuzAlbum {
  id: string;
  title: string;
  artist: string;
  cover: string;
  year: number;
  label: string;
  tracks: QobuzTrack[];
}

export interface QobuzToken {
  id: string;
  name: string;
  token: string;
  isActive: boolean;
}

export interface KkboxTrack {
  id: string;
  encryptedId: string;
  title: string;
  artist: string;
  cover: string;
  album: string;
  albumId: string;
  albumArtist?: string;
  trackNumber: number;
  duration: number;
  qualities: string[];
  releaseDate?: string;
}

export interface KkboxAlbum {
  id: string;
  encryptedId: string;
  title: string;
  artist: string;
  cover: string;
  year: string;
  tracks: KkboxTrack[];
}

export interface KkboxSession {
  sid: string;
  licContentKey: string;
  highQuality: boolean;
  kkid: string;
  email: string;
  loggedIn: boolean;
}

export interface KkboxAccount {
  id: string;
  name: string;
  email: string;
  password: string;
  session: KkboxSession | null;
  isActive: boolean;
}

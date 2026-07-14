import { create } from 'zustand';
import type { Song } from '../types';

interface AlbumDetailData {
  id: string;
  cover: string;
  name: string;
  artist: string;
}

interface SingerDetailData {
  id: string;
  name: string;
  picture: string;
}

interface UIState {
  playerOverlayVisible: boolean;
  queueVisible: boolean;
  drawerVisible: boolean;
  pickerVisible: boolean;
  pickerSong: Song | null;
  contactVisible: boolean;
  isDragging: boolean;
  seekRatio: number;
  albumDetailVisible: boolean;
  albumDetailData: AlbumDetailData | null;
  singerDetailVisible: boolean;
  singerDetailData: SingerDetailData | null;
  downloadQueueVisible: boolean;
  qobuzTokenVisible: boolean;
  kkboxAccountVisible: boolean;
  setPlayerOverlayVisible: (visible: boolean) => void;
  setQueueVisible: (visible: boolean) => void;
  setDrawerVisible: (visible: boolean) => void;
  setPicker: (visible: boolean, song?: Song | null) => void;
  setContactVisible: (visible: boolean) => void;
  setDragging: (dragging: boolean, seekRatio?: number) => void;
  setAlbumDetailVisible: (visible: boolean, data?: AlbumDetailData | null) => void;
  setSingerDetailVisible: (visible: boolean, data?: SingerDetailData | null) => void;
  setDownloadQueueVisible: (visible: boolean) => void;
  setQobuzTokenVisible: (visible: boolean) => void;
  setKkboxAccountVisible: (visible: boolean) => void;
  showLyricsTranslation: boolean;
  setShowLyricsTranslation: (visible: boolean) => void;
  currentLineIndex: number;
  setCurrentLineIndex: (index: number) => void;
}

export const useUIStore = create<UIState>()((set) => ({
  playerOverlayVisible: false,
  queueVisible: false,
  drawerVisible: false,
  pickerVisible: false,
  pickerSong: null,
  contactVisible: false,
  isDragging: false,
  seekRatio: 0,
  albumDetailVisible: false,
  albumDetailData: null,
  singerDetailVisible: false,
  singerDetailData: null,
  downloadQueueVisible: false,
  qobuzTokenVisible: false,
  kkboxAccountVisible: false,
  setPlayerOverlayVisible: (visible) => set({ playerOverlayVisible: visible }),
  setQueueVisible: (visible) => set({ queueVisible: visible }),
  setDrawerVisible: (visible) => set({ drawerVisible: visible }),
  setPicker: (visible, song) => set({ pickerVisible: visible, pickerSong: song ?? null }),
  setContactVisible: (visible) => set({ contactVisible: visible }),
  setDragging: (dragging, seekRatio) => set({ isDragging: dragging, seekRatio: seekRatio ?? 0 }),
  setAlbumDetailVisible: (visible, data) => set({ albumDetailVisible: visible, albumDetailData: data ?? null }),
  setSingerDetailVisible: (visible, data) => set({ singerDetailVisible: visible, singerDetailData: data ?? null }),
  setDownloadQueueVisible: (visible) => set({ downloadQueueVisible: visible }),
  setQobuzTokenVisible: (visible) => set({ qobuzTokenVisible: visible }),
  setKkboxAccountVisible: (visible) => set({ kkboxAccountVisible: visible }),
  showLyricsTranslation: true,
  setShowLyricsTranslation: (visible) => set({ showLyricsTranslation: visible }),
  currentLineIndex: -1,
  setCurrentLineIndex: (index) => set({ currentLineIndex: index }),
}));

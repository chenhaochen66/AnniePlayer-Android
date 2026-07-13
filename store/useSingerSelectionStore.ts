import { create } from 'zustand';
import type { Song } from '../types';

interface SingerSelectionState {
  selectedSongs: Song[];
  overlayVisible: boolean;
  addSong: (song: Song) => void;
  removeSong: (id: string) => void;
  clearAll: () => void;
  setOverlayVisible: (v: boolean) => void;
}

export const useSingerSelectionStore = create<SingerSelectionState>()((set, get) => ({
  selectedSongs: [],
  overlayVisible: false,
  addSong: (song) => {
    if (get().selectedSongs.some((s) => s.id === song.id)) return;
    set({ selectedSongs: [...get().selectedSongs, song] });
  },
  removeSong: (id) => set({ selectedSongs: get().selectedSongs.filter((s) => s.id !== id) }),
  clearAll: () => set({ selectedSongs: [] }),
  setOverlayVisible: (v) => set({ overlayVisible: v }),
}));

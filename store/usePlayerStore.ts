import { create } from 'zustand';
import { persist, createJSONStorage } from 'zustand/middleware';
import AsyncStorage from '@react-native-async-storage/async-storage';
import type { Song, LoopMode, Quality } from '../types';

interface PlayerState {
  queue: Song[];
  currentIndex: number;
  currentSong: Song | null;
  isPlaying: boolean;
  loopMode: LoopMode;
  currentTime: number;
  duration: number;
  bufferedTime: number;
  volume: number;
  playbackId: number;
  downloadedIds: string[];
  isLyricsVisible: boolean;
  setQueue: (songs: Song[], startIndex?: number) => void;
  addToQueue: (song: Song) => void;
  removeFromQueue: (index: number) => void;
  next: () => void;
  previous: () => void;
  seekTo: (index: number) => void;
  playIndex: (index: number) => void;
  togglePlay: () => void;
  setIsPlaying: (playing: boolean) => void;
  setLoopMode: (mode: LoopMode) => void;
  setCurrentTime: (time: number) => void;
  setDuration: (duration: number) => void;
  setBufferedTime: (time: number) => void;
  syncProgress: (currentTime: number, duration: number, bufferedTime: number) => void;
  setVolume: (volume: number) => void;
  updateSongPlayUrl: (id: string, url: string) => void;
  updateSongLyric: (id: string, lyric: string) => void;
  setLyricsVisible: (visible: boolean) => void;
  addDownloadedId: (id: string) => void;
}

export const usePlayerStore = create<PlayerState>()(
  persist(
    (set, get) => ({
      queue: [],
      currentIndex: 0,
      currentSong: null,
      isPlaying: false,
      loopMode: 'sequential',
      currentTime: 0,
      duration: 0,
      bufferedTime: 0,
      volume: 0.8,
      playbackId: 0,
      downloadedIds: [],
      isLyricsVisible: false,
      setQueue: (songs, startIndex = 0) => {
        set({ queue: songs, currentIndex: startIndex, currentSong: songs[startIndex] || null, isPlaying: true });
      },
      addToQueue: (song) => {
        const { queue } = get();
        if (queue.some((s) => s.id === song.id)) return;
        set({ queue: [...queue, song] });
      },
      removeFromQueue: (index) => {
        const { queue, currentIndex } = get();
        const newQueue = queue.filter((_, i) => i !== index);
        const newIndex = index < currentIndex ? currentIndex - 1 : currentIndex;
        set({ queue: newQueue, currentIndex: Math.min(newIndex, newQueue.length - 1), currentSong: newQueue[Math.min(newIndex, newQueue.length - 1)] || null });
      },
      next: () => {
        const { queue, currentIndex, loopMode, playbackId } = get();
        if (queue.length === 0) return;
        let nextIndex;
        if (loopMode === 'shuffle') {
          const available = queue.map((_, i) => i).filter((i) => i !== currentIndex);
          nextIndex = available.length > 0 ? available[Math.floor(Math.random() * available.length)] : currentIndex;
        } else if (loopMode === 'single') {
          nextIndex = currentIndex;
        } else {
          nextIndex = (currentIndex + 1) % queue.length;
        }
        set({ currentIndex: nextIndex, currentSong: queue[nextIndex], playbackId: playbackId + 1 });
      },
      previous: () => {
        const { queue, currentIndex, playbackId } = get();
        if (queue.length === 0) return;
        let prevIndex;
        if (get().loopMode === 'shuffle') {
          const available = queue.map((_, i) => i).filter((i) => i !== currentIndex);
          prevIndex = available.length > 0 ? available[Math.floor(Math.random() * available.length)] : currentIndex;
        } else {
          prevIndex = (currentIndex - 1 + queue.length) % queue.length;
        }
        set({ currentIndex: prevIndex, currentSong: queue[prevIndex], playbackId: playbackId + 1 });
      },
      seekTo: (index) => {
        const { queue } = get();
        if (index < 0 || index >= queue.length) return;
        set({ currentIndex: index, currentSong: queue[index] });
      },
      playIndex: (index) => {
        const { queue, playbackId } = get();
        if (index < 0 || index >= queue.length) return;
        set({ currentIndex: index, currentSong: queue[index], playbackId: playbackId + 1, isPlaying: true });
      },
      togglePlay: () => set((state) => ({ isPlaying: !state.isPlaying })),
      setIsPlaying: (playing) => set({ isPlaying: playing }),
      setLoopMode: (mode) => set({ loopMode: mode }),
      setCurrentTime: (time) => set({ currentTime: time }),
      setDuration: (duration) => set({ duration }),
      setBufferedTime: (time) => set({ bufferedTime: time }),
      syncProgress: (currentTime, duration, bufferedTime) => set({ currentTime, duration, bufferedTime }),
      setVolume: (volume) => set({ volume }),
      updateSongPlayUrl: (id, playUrl) => {
        const { queue, currentSong } = get();
        set({ queue: queue.map((s) => s.id === id ? { ...s, playUrl } : s), currentSong: currentSong?.id === id ? { ...currentSong, playUrl } : currentSong });
      },
      updateSongLyric: (id, lyric) => {
        const { queue, currentSong } = get();
        set({ queue: queue.map((s) => s.id === id ? { ...s, lyric } : s), currentSong: currentSong?.id === id ? { ...currentSong, lyric } : currentSong });
      },
      setLyricsVisible: (visible) => set({ isLyricsVisible: visible }),
      addDownloadedId: (id) => {
        const { downloadedIds } = get();
        if (downloadedIds.includes(id)) return;
        set({ downloadedIds: [...downloadedIds, id] });
      },
    }),
    { name: 'player-storage', storage: createJSONStorage(() => AsyncStorage), partialize: (state) => ({ volume: state.volume, loopMode: state.loopMode, downloadedIds: state.downloadedIds }) },
  ),
);

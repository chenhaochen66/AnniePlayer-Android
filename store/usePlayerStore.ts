import { create } from 'zustand';
import { persist, createJSONStorage } from 'zustand/middleware';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { NativeModules, Image } from 'react-native';
import type { Song, LoopMode } from '../types';

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
  next: (forceNext?: boolean) => void;
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
  updateSongLyric: (id: string, lrc: string, tlyric?: string) => void;
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
        const { loopMode } = get();
        set({ queue: songs, currentIndex: startIndex, currentSong: songs[startIndex] || null, isPlaying: true, playbackId: get().playbackId + 1 });
        const items = songs.map(s => {
          const anyS = s as any;
          return {
            id: s.id, platform: anyS.platform || 'kuwo',
            title: s.title || '', artist: s.artist || '',
            cover: s.cover || '', album: s.album || '',
            ...(anyS.platform === 'qobuz' && anyS.qobuzToken ? { token: anyS.qobuzToken } : {}),
            ...(anyS.platform === 'kkbox' && anyS.playUrl ? { url: anyS.playUrl } : {}),
          };
        });
        NativeModules.TrackPlayerModule.setQueue(items, startIndex, loopMode);
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
        const { queue, currentIndex, loopMode } = get();
        if (queue.length === 0) return;
        let nextIndex: number;
        if (loopMode === 'shuffle') {
          nextIndex = Math.floor(Math.random() * queue.length);
        } else if (loopMode === 'single') {
          nextIndex = currentIndex;
        } else {
          nextIndex = (currentIndex + 1) % queue.length;
        }
        const nextSong = queue[nextIndex];
        set({ currentIndex: nextIndex, currentSong: nextSong, playbackId: get().playbackId + 1 });
        if (nextSong?.cover) Image.prefetch(nextSong.cover);
        NativeModules.TrackPlayerModule.skipNext();
      },
      previous: () => {
        const { queue, currentIndex, loopMode } = get();
        if (queue.length === 0) return;
        let prevIndex: number;
        if (loopMode === 'shuffle') {
          prevIndex = Math.floor(Math.random() * queue.length);
        } else {
          prevIndex = (currentIndex - 1 + queue.length) % queue.length;
        }
        const prevSong = queue[prevIndex];
        set({ currentIndex: prevIndex, currentSong: prevSong, playbackId: get().playbackId + 1 });
        if (prevSong?.cover) Image.prefetch(prevSong.cover);
        NativeModules.TrackPlayerModule.skipPrevious();
      },
      seekTo: (index) => {
        const { queue } = get();
        if (index < 0 || index >= queue.length) return;
        const song = queue[index];
        set({ currentIndex: index, currentSong: song });
        if (song?.cover) Image.prefetch(song.cover);
      },
      playIndex: (index) => {
        const { queue } = get();
        if (index < 0 || index >= queue.length) return;
        const song = queue[index];
        set({ currentIndex: index, currentSong: song, playbackId: get().playbackId + 1 });
        if (song?.cover) Image.prefetch(song.cover);
        NativeModules.TrackPlayerModule.skipTo(index);
      },
      togglePlay: () => set((state) => ({ isPlaying: !state.isPlaying })),
      setIsPlaying: (playing) => set({ isPlaying: playing }),
      setLoopMode: (mode) => {
        set({ loopMode: mode });
        NativeModules.TrackPlayerModule.setAppLoopMode(mode);
      },
      setCurrentTime: (time) => set({ currentTime: time }),
      setDuration: (duration) => set({ duration }),
      setBufferedTime: (time) => set({ bufferedTime: time }),
      syncProgress: (currentTime, duration, bufferedTime) => set({ currentTime, duration, bufferedTime }),
      setVolume: (volume) => set({ volume }),
      updateSongPlayUrl: (id, playUrl) => {
        const { queue, currentSong } = get();
        set({ queue: queue.map((s) => s.id === id ? { ...s, playUrl } : s), currentSong: currentSong?.id === id ? { ...currentSong, playUrl } : currentSong });
      },
      updateSongLyric: (id, lrc, tlyric) => {
        const { queue, currentSong } = get();
        set({ queue: queue.map((s) => s.id === id ? { ...s, lyric: lrc, tlyric } : s), currentSong: currentSong?.id === id ? { ...currentSong, lyric: lrc, tlyric } : currentSong });
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

import React, { createContext, useContext, useEffect, useMemo, useCallback } from 'react';
import { NativeModules, AppState } from 'react-native';
import TrackPlayer, { useProgress, useTrackPlayerEvents, Event } from 'react-native-track-player';
import { useAudio, AudioControls } from '../hooks/useAudio';
import { usePlayerStore } from '../store/usePlayerStore';
import { fetchLyric } from '../services/musicApi';

const AudioCtx = createContext<AudioControls | null>(null);

export function useSharedAudio(): AudioControls {
  const ctx = useContext(AudioCtx);
  if (!ctx) throw new Error('useSharedAudio must be inside AudioProvider');
  return ctx;
}

export function AudioProvider({ children }: { children: React.ReactNode }) {
  const controls = useAudio();
  const currentSong = usePlayerStore((s) => s.currentSong);
  const playbackId = usePlayerStore((s) => s.playbackId);
  const setIsPlaying = usePlayerStore((s) => s.setIsPlaying);
  const syncProgress = usePlayerStore((s) => s.syncProgress);

  const { position, duration, buffered } = useProgress(500);
  useEffect(() => {
    syncProgress(position, duration, buffered);
  }, [position, duration, buffered, syncProgress]);

  const onTrackChanged = useCallback(async (event: any) => {
    const absoluteIndex = event.absoluteIndex;
    if (absoluteIndex == null || absoluteIndex < 0) return;
    const { queue, currentIndex, playbackId } = usePlayerStore.getState();
    if (absoluteIndex < queue.length && absoluteIndex !== currentIndex) {
      const song = queue[absoluteIndex];
      usePlayerStore.setState({
        currentIndex: absoluteIndex,
        currentSong: song,
        playbackId: playbackId + 1,
      });
    }
  }, []);

  const onPlayWhenReadyChanged = useCallback((event: any) => {
    usePlayerStore.getState().setIsPlaying(event.playWhenReady);
  }, []);

  const trackEvents = useMemo(() => [Event.PlaybackActiveTrackChanged], []);
  const playWhenReadyEvents = useMemo(() => [Event.PlaybackPlayWhenReadyChanged], []);
  useTrackPlayerEvents(trackEvents, onTrackChanged);
  useTrackPlayerEvents(playWhenReadyEvents, onPlayWhenReadyChanged);

  useEffect(() => {
    const sub = AppState.addEventListener('change', async (state) => {
      if (state !== 'active') return;
      try {
        const idx = await NativeModules.TrackPlayerModule.getQueueIndex();
        if (idx == null || idx < 0) return;
        const { queue, currentIndex } = usePlayerStore.getState();
        if (idx < queue.length && idx !== currentIndex) {
          const song = queue[idx];
          usePlayerStore.setState({ currentIndex: idx, currentSong: song });
        }
      } catch {}
    });
    return () => sub.remove();
  }, []);

  useEffect(() => {
    if (!currentSong?.id || currentSong.lyric !== undefined) return;
    const songId = currentSong.id;
    fetchLyric(songId, currentSong.title, currentSong.artist).then((lyric) => {
      if (usePlayerStore.getState().currentSong?.id === songId) {
        usePlayerStore.getState().updateSongLyric(songId, lyric);
      }
    }).catch(() => {});
  }, [currentSong?.id]);

  const togglePlay = useCallback(() => {
    const shouldPlay = !usePlayerStore.getState().isPlaying;
    (async () => {
      if (shouldPlay) {
        try { await TrackPlayer.play(); } catch { return; }
        setIsPlaying(true);
      } else {
        try { await TrackPlayer.pause(); } catch { return; }
        setIsPlaying(false);
      }
    })();
  }, [setIsPlaying]);

  const api = useMemo(() => ({
    ...controls,
    togglePlay,
  }), [controls, togglePlay]);

  return <AudioCtx.Provider value={api}>{children}</AudioCtx.Provider>;
}

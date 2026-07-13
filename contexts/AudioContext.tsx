import React, { createContext, useContext, useEffect, useMemo, useCallback } from 'react';
import { NativeModules, AppState } from 'react-native';
import TrackPlayer, { useTrackPlayerEvents, Event } from 'react-native-track-player';
import { useAudio, AudioControls } from '../hooks/useAudio';
import { usePlayerStore } from '../store/usePlayerStore';
import { fetchLyric } from '../services/cnmusicApi';
import { usePlayerProgress } from './PlayerProgressContext';



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
  const { position, duration } = usePlayerProgress();
  useEffect(() => {
    const timer = setInterval(async () => {
      try {
        const p = await TrackPlayer.getProgress();
        position.value = p.position;
        duration.value = p.duration;
      } catch {}
    }, 500);
    return () => clearInterval(timer);
  }, []);

  const onPlayWhenReadyChanged = useCallback((event: any) => {
    usePlayerStore.getState().setIsPlaying(event.playWhenReady);
  }, []);

  const playWhenReadyEvents = useMemo(() => [Event.PlaybackPlayWhenReadyChanged], []);
  useTrackPlayerEvents(playWhenReadyEvents, onPlayWhenReadyChanged);

  const remoteNextPrevEvents = useMemo(() => [Event.RemoteNext, Event.RemotePrevious], []);
  const onRemoteNextPrev = useCallback((event: any) => {
    if (event.type === Event.RemoteNext) usePlayerStore.getState().next();
    else usePlayerStore.getState().previous();
  }, []);
  useTrackPlayerEvents(remoteNextPrevEvents, onRemoteNextPrev);

  const syncFromNative = useCallback(async () => {
    try {
      const idx = await NativeModules.TrackPlayerModule.getQueueIndex();
      if (idx == null || idx < 0) return;
      const { queue, currentIndex } = usePlayerStore.getState();
      if (idx < queue.length && idx !== currentIndex) {
        usePlayerStore.setState({ currentIndex: idx, currentSong: queue[idx], playbackId: usePlayerStore.getState().playbackId + 1 });
      }
      const playbackState = await NativeModules.TrackPlayerModule.getPlaybackState();
      if (playbackState?.state) {
        const s = playbackState.state;
        usePlayerStore.getState().setIsPlaying(s === 'playing' || s === 'ready' || s === 'buffering' || s === 'loading');
      }
    } catch {}
  }, []);

  const trackChangeEvents = useMemo(() => [Event.PlaybackActiveTrackChanged], []);
  useTrackPlayerEvents(trackChangeEvents, syncFromNative);

  useEffect(() => {
    const interval = setInterval(syncFromNative, 5000);
    return () => clearInterval(interval);
  }, [syncFromNative]);

  useEffect(() => {
    const sub = AppState.addEventListener('change', async (state) => {
      if (state !== 'active') return;
      try {
        const idx = await NativeModules.TrackPlayerModule.getQueueIndex();
        if (idx != null && idx >= 0) {
          const { queue, currentIndex } = usePlayerStore.getState();
          if (idx < queue.length && idx !== currentIndex) {
            usePlayerStore.setState({ currentIndex: idx, currentSong: queue[idx] });
          }
        }
      } catch {}
      try {
        const playbackState = await NativeModules.TrackPlayerModule.getPlaybackState();
        if (playbackState?.state) {
          const s = playbackState.state;
          usePlayerStore.getState().setIsPlaying(s === 'playing' || s === 'ready' || s === 'buffering' || s === 'loading');
        }
      } catch {}
    });
    return () => sub.remove();
  }, []);

  useEffect(() => {
    if (!currentSong?.id || currentSong.lyric !== undefined) return;
    const songId = currentSong.id;
    fetchLyric(songId, currentSong.title, currentSong.artist).then(({ lrc, tlyric }) => {
      if (usePlayerStore.getState().currentSong?.id === songId) {
        usePlayerStore.getState().updateSongLyric(songId, lrc, tlyric ?? undefined);
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

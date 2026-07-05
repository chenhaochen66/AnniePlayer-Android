import React, { createContext, useContext, useEffect, useRef, useMemo, useCallback } from 'react';
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
  const lastKey = useRef<string>('');
  const generation = useRef(0);
  const isUpdatingTrack = useRef(false);
  const nativeQueueBaseIndex = useRef(0);

  const isPlaying = usePlayerStore((s) => s.isPlaying);
  const lastPlaybackId = useRef(playbackId);
  const { position, duration, buffered } = useProgress(500);
  useEffect(() => {
    if (!isPlaying) return;
    if (position === 0 && duration === 0) return;
    if (isUpdatingTrack.current) return;
    if (playbackId !== lastPlaybackId.current) {
      lastPlaybackId.current = playbackId;
      return;
    }
    syncProgress(position, duration, buffered);
  }, [position, duration, buffered, playbackId, isPlaying]);

  const onTrackChanged = useCallback(async (event: any) => {
    if (event.index == null || event.index < 0) return;
    if (isUpdatingTrack.current) return;
    const { queue, currentIndex, playbackId } = usePlayerStore.getState();
    const actualIndex = nativeQueueBaseIndex.current + event.index;
    if (actualIndex < queue.length && actualIndex !== currentIndex) {
      const song = queue[actualIndex];
      usePlayerStore.setState({
        currentIndex: actualIndex,
        currentSong: song,
      });
      lastKey.current = `${song?.id || ''}:${playbackId}`;
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
        const activeIdx = await TrackPlayer.getActiveTrackIndex();
        if (activeIdx == null || activeIdx < 0) return;
        const actualIndex = nativeQueueBaseIndex.current + activeIdx;
        const { queue, currentIndex, playbackId } = usePlayerStore.getState();
        if (actualIndex < queue.length && actualIndex !== currentIndex) {
          const song = queue[actualIndex];
          usePlayerStore.setState({
            currentIndex: actualIndex,
            currentSong: song,
          });
          lastKey.current = `${song?.id || ''}:${playbackId}`;
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

  useEffect(() => {
    const key = `${currentSong?.id || ''}:${playbackId}`;
    if (!currentSong?.id || key === lastKey.current) return;
    lastKey.current = key;
    generation.current++;
    isUpdatingTrack.current = true;
    const myGen = generation.current;
    (async () => {
      try {
        const { queue, loopMode } = usePlayerStore.getState();
        const items = queue.map(s => ({
          id: s.id, platform: s.platform || 'kuwo',
          title: s.title || '', artist: s.artist || '',
          cover: s.cover || '', album: s.album || '',
        }));
        await NativeModules.TrackPlayerModule.setPreloadQueue(items);
        await NativeModules.TrackPlayerModule.stopPreloadChain();
        if (generation.current !== myGen) return;

        const idx = usePlayerStore.getState().currentIndex;
        await NativeModules.TrackPlayerModule.playIndex(idx);
        nativeQueueBaseIndex.current = idx;
        if (generation.current !== myGen) return;

        if (loopMode !== 'single' && items.length > 1) {
          await NativeModules.TrackPlayerModule.startPreloadChain();
        }
      } catch (e) {
        console.warn('[audio] native playback failed:', e);
      } finally {
        if (generation.current === myGen) {
          isUpdatingTrack.current = false;
        }
      }
    })();
  }, [currentSong?.id, playbackId]);

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

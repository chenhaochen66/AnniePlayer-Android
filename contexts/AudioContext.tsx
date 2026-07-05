import React, { createContext, useContext, useEffect, useRef, useMemo, useCallback } from 'react';
import { AppState, NativeModules } from 'react-native';
import TrackPlayer, { useProgress, useTrackPlayerEvents, Event } from 'react-native-track-player';
import { useAudio, AudioControls } from '../hooks/useAudio';
import { usePlayerStore } from '../store/usePlayerStore';
import { fetchPlayUrlBySong, fetchLyric } from '../services/musicApi';
import { fetchAndCacheAudio } from '../services/assetProxy';

const AudioCtx = createContext<AudioControls | null>(null);

export function useSharedAudio(): AudioControls {
  const ctx = useContext(AudioCtx);
  if (!ctx) throw new Error('useSharedAudio must be inside AudioProvider');
  return ctx;
}

export function AudioProvider({ children }: { children: React.ReactNode }) {
  const controls = useAudio();
  const { play, load, unload } = controls;
  const currentSong = usePlayerStore((s) => s.currentSong);
  const playbackId = usePlayerStore((s) => s.playbackId);
  const setIsPlaying = usePlayerStore((s) => s.setIsPlaying);
  const syncProgress = usePlayerStore((s) => s.syncProgress);
  const next = usePlayerStore((s) => s.next);
  const lastKey = useRef<string>('');
  const generation = useRef(0);
  const isUpdatingTrack = useRef(false);
  const lastAdvancedSongId = useRef<string | null>(null);
  const pendingSeekUntil = useRef(0);
  const nativeQueueBaseIndex = useRef(0);

  const isPlaying = usePlayerStore((s) => s.isPlaying);
  const lastPlaybackId = useRef(playbackId);
  const { position, duration, buffered } = useProgress(500);
  useEffect(() => {
    if (Date.now() < pendingSeekUntil.current) return;
    if (!isPlaying) return;
    if (position === 0 && duration === 0) return;
    if (isUpdatingTrack.current) return;
    if (playbackId !== lastPlaybackId.current) {
      lastPlaybackId.current = playbackId;
      return;
    }
    syncProgress(position, duration, buffered);
    const nearEnd = currentSong?.id && duration > 1 && position >= duration - 1;
    if (nearEnd) console.log('[adv] progress nearEnd', currentSong?.id?.slice(0,12), 'updating:', isUpdatingTrack.current, 'lastAdv:', lastAdvancedSongId.current?.slice(0,12));
    if (nearEnd && lastAdvancedSongId.current !== currentSong.id) {
      console.log('[adv] progress -> next()', currentSong?.id?.slice(0,12));
      isUpdatingTrack.current = true;
      lastAdvancedSongId.current = currentSong.id;
      next();
    }
  }, [position, duration, buffered, currentSong?.id, playbackId, next, isPlaying]);

  const notifyBeforeSeek = useCallback(() => {
    pendingSeekUntil.current = Date.now() + 2000;
  }, []);

  useTrackPlayerEvents([Event.PlaybackActiveTrackChanged], async (event) => {
    if (event.index == null || event.index < 0) return;
    const { queue, currentIndex } = usePlayerStore.getState();
    const actualIndex = nativeQueueBaseIndex.current + event.index;
    if (actualIndex < queue.length && actualIndex !== currentIndex) {
      isUpdatingTrack.current = true;
      console.log('[adv] event update', queue[actualIndex]?.id?.slice(0,12), 'idx', actualIndex);
      usePlayerStore.setState({
        currentIndex: actualIndex,
        currentSong: queue[actualIndex],
      });
    }
  });

  useTrackPlayerEvents([Event.PlaybackPlayWhenReadyChanged], (event) => {
    usePlayerStore.getState().setIsPlaying(event.playWhenReady);
  });



  useEffect(() => {
    const sub = AppState.addEventListener('change', async (state) => {
      if (state === 'active' && currentSong?.id) {
        try {
          const activeIdx = await TrackPlayer.getActiveTrackIndex();
          const { currentIndex, queue } = usePlayerStore.getState();
          if (isUpdatingTrack.current) {
            console.log('[adv] foreground skip, loading in progress');
            return;
          }
          if (activeIdx != null && activeIdx >= 0 && nativeQueueBaseIndex.current + activeIdx < queue.length) {
            const actualIndex = nativeQueueBaseIndex.current + activeIdx;
            if (actualIndex !== currentIndex) {
              console.log('[adv] foreground sync', queue[actualIndex]?.id?.slice(0,12), 'activeIdx', activeIdx);
              usePlayerStore.setState({
                currentIndex: actualIndex,
                currentSong: queue[actualIndex],
              });
            }
          } else if (activeIdx == null || activeIdx < 0) {
            console.log('[adv] foreground no active track, restart', currentSong?.id?.slice(0,12));
            usePlayerStore.getState().playIndex(currentIndex);
          }
        } catch {}
      }
    });
    return () => sub.remove();
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
        if (currentSong.lyric === undefined) {
          const lyricGen = generation.current;
          fetchLyric(currentSong.id, currentSong.title, currentSong.artist).then((lyric) => {
            if (generation.current === lyricGen) {
              usePlayerStore.getState().updateSongLyric(currentSong.id, lyric);
            }
          }).catch(() => {});
        }

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
        console.warn('[audio] native failed, JS fallback:', e);
        let url = currentSong.playUrl;
        if (!url) {
          url = await fetchPlayUrlBySong(currentSong);
          usePlayerStore.getState().updateSongPlayUrl(currentSong.id, url);
        }
        if (generation.current !== myGen) return;
        fetchAndCacheAudio(url, currentSong.id).catch(() => {});
        if (generation.current !== myGen) return;
        await unload().catch(() => {});
        await load(url);
        nativeQueueBaseIndex.current = usePlayerStore.getState().currentIndex;
        if (generation.current !== myGen) return;
        if (usePlayerStore.getState().isPlaying) {
          await play();
        }
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
    notifyBeforeSeek,
  }), [controls, togglePlay, notifyBeforeSeek]);

  return <AudioCtx.Provider value={api}>{children}</AudioCtx.Provider>;
}

import React, { createContext, useContext, useEffect, useRef, useMemo, useCallback } from 'react';
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
  const { play, pause, load, unload, state, setOnFinish } = controls;
  const currentSong = usePlayerStore((s) => s.currentSong);
  const playbackId = usePlayerStore((s) => s.playbackId);
  const setIsPlaying = usePlayerStore((s) => s.setIsPlaying);
  const next = usePlayerStore((s) => s.next);
  const lastKey = useRef<string>('');
  const generation = useRef(0);
  const isUpdatingTrack = useRef(false);
  const audioOpRef = useRef<Promise<void>>(Promise.resolve());
  const stateRef = useRef(state);
  stateRef.current = state;

  useEffect(() => {
    setOnFinish(() => next());
  }, [next, setOnFinish]);

  useEffect(() => {
    const key = `${currentSong?.id || ''}:${playbackId}`;
    if (!currentSong?.id || key === lastKey.current) return;
    lastKey.current = key;
    generation.current++;
    isUpdatingTrack.current = true;
    const myGen = generation.current;

    (async () => {
      try {
        await unload().catch(() => {});

        let url = currentSong.playUrl;
        if (!url) {
          console.log('[audio] fetching playUrl for', currentSong.id);
          url = await fetchPlayUrlBySong(currentSong);
          usePlayerStore.getState().updateSongPlayUrl(currentSong.id, url);
        }
        if (generation.current !== myGen) return;
        try {
          url = await fetchAndCacheAudio(url, currentSong.id);
        } catch {}
        if (generation.current !== myGen) return;
        const lyricGen = generation.current;
        if (currentSong.lyric === undefined) {
          fetchLyric(currentSong.id, currentSong.title, currentSong.artist).then((lyric) => {
            if (generation.current === lyricGen) {
              usePlayerStore.getState().updateSongLyric(currentSong.id, lyric);
            }
          }).catch(() => {});
        }
        console.log('[audio] streaming:', currentSong.id);
        await load(url);
        console.log('[audio] load ok');
        if (generation.current !== myGen) return;
        if (usePlayerStore.getState().isPlaying) {
          await play();
          console.log('[audio] play after load');
        }
      } catch (e) {
        console.warn('[audio] operation failed:', e);
      } finally {
        if (generation.current === myGen) {
          isUpdatingTrack.current = false;
        }
      }
    })();
  }, [currentSong?.id, playbackId]);

  useEffect(() => {
    if (isUpdatingTrack.current) return;
    if (state.status === 'playing') setIsPlaying(true);
    else if (state.status === 'paused') setIsPlaying(false);
  }, [state.status]);

  const togglePlay = useCallback(() => {
    const shouldPlay = !usePlayerStore.getState().isPlaying;
    audioOpRef.current = audioOpRef.current.then(async () => {
      if (shouldPlay) {
        try {
          await play();
        } catch (e) {
          console.warn('[audio] play failed:', e);
          return;
        }
        setIsPlaying(true);
      } else {
        try {
          await pause();
        } catch (e) {
          console.warn('[audio] pause failed:', e);
          return;
        }
        setIsPlaying(false);
      }
    });
  }, [pause, play, setIsPlaying]);

  const api = useMemo(() => ({
    ...controls,
    togglePlay,
  }), [controls, togglePlay]);

  return <AudioCtx.Provider value={api}>{children}</AudioCtx.Provider>;
}
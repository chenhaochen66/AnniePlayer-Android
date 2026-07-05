import React, { createContext, useContext, useEffect, useRef, useMemo, useCallback } from 'react';
import { AppState } from 'react-native';
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
  const { position, duration, buffered } = useProgress(500);
  useEffect(() => {
    if (Date.now() < pendingSeekUntil.current) return;
    if (!isPlaying) return;
    syncProgress(position, duration, buffered);
    if (currentSong?.id && duration > 1 && position >= duration - 1 && !isUpdatingTrack.current && lastAdvancedSongId.current !== currentSong.id) {
      isUpdatingTrack.current = true;
      lastAdvancedSongId.current = currentSong.id;
      next();
    }
  }, [position, duration, buffered, currentSong?.id, next, isPlaying]);

  const notifyBeforeSeek = useCallback(() => {
    pendingSeekUntil.current = Date.now() + 2000;
  }, []);

  useTrackPlayerEvents([Event.PlaybackActiveTrackChanged], async (event) => {
    if (event.track == null || event.track <= 0) return;
    const { queue, currentIndex } = usePlayerStore.getState();
    const actualIndex = nativeQueueBaseIndex.current + event.track;
    if (actualIndex < queue.length && actualIndex !== currentIndex) {
      isUpdatingTrack.current = true;
      usePlayerStore.setState({
        currentIndex: actualIndex,
        currentSong: queue[actualIndex],
      });
    }
  });

  useEffect(() => {
    const sub = AppState.addEventListener('change', async (state) => {
      if (state === 'active' && currentSong?.id) {
        try {
          const activeIdx = await TrackPlayer.getActiveTrackIndex();
          const { currentIndex, queue } = usePlayerStore.getState();
          if (activeIdx != null && activeIdx >= 0 && nativeQueueBaseIndex.current + activeIdx < queue.length) {
            const actualIndex = nativeQueueBaseIndex.current + activeIdx;
            if (actualIndex !== currentIndex) {
              usePlayerStore.setState({
                currentIndex: actualIndex,
                currentSong: queue[actualIndex],
              });
            }
          } else if (activeIdx == null || activeIdx < 0) {
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
        const lyricGen = generation.current;
        if (currentSong.lyric === undefined) {
          fetchLyric(currentSong.id, currentSong.title, currentSong.artist).then((lyric) => {
            if (generation.current === lyricGen) {
              usePlayerStore.getState().updateSongLyric(currentSong.id, lyric);
            }
          }).catch(() => {});
        }

        const activeIdx = await TrackPlayer.getActiveTrackIndex().catch(() => -1);
        if (activeIdx != null && activeIdx >= 0 && generation.current === myGen) {
          const activeTrack = await TrackPlayer.getTrack(activeIdx).catch(() => null);
          if (activeTrack?.id === currentSong?.id && generation.current === myGen) {
            const ps = await TrackPlayer.getPlaybackState().catch(() => ({ state: 'unknown' }));
            if (ps.state !== 'ended' && ps.state !== 'stopped' && ps.state !== 'none') {
              isUpdatingTrack.current = false;
              return;
            }
          }
        }

        await unload().catch(() => {});

        let url = currentSong.playUrl;
        if (!url) {
          url = await fetchPlayUrlBySong(currentSong);
          usePlayerStore.getState().updateSongPlayUrl(currentSong.id, url);
        }
        if (generation.current !== myGen) return;
        try {
          url = await fetchAndCacheAudio(url, currentSong.id);
        } catch {}
        if (generation.current !== myGen) return;
        await load(url);
        nativeQueueBaseIndex.current = usePlayerStore.getState().currentIndex;
        if (generation.current !== myGen) return;
        if (usePlayerStore.getState().isPlaying) {
          await play();
        }
        if (generation.current !== myGen) return;

        const { queue, currentIndex, loopMode } = usePlayerStore.getState();
        if (loopMode !== 'single') {
          for (let i = currentIndex + 1; i < queue.length; i++) {
            if (generation.current !== myGen) break;
            const song = queue[i];
            let songUrl = song.playUrl;
            if (!songUrl) {
              try {
                songUrl = await fetchPlayUrlBySong(song);
                usePlayerStore.getState().updateSongPlayUrl(song.id, songUrl);
              } catch { continue; }
            }
            try {
              await TrackPlayer.add({ id: song.id, url: songUrl });
            } catch { break; }
          }
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

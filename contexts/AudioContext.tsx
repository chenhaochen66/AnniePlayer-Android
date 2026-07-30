import React, { createContext, useContext, useEffect, useMemo, useCallback, useRef } from 'react';
import { NativeModules, AppState } from 'react-native';
import TrackPlayer, { useTrackPlayerEvents, Event } from 'react-native-track-player';
import { useAudio, AudioControls } from '../hooks/useAudio';
import { usePlayerStore } from '../store/usePlayerStore';
import { fetchLyric } from '../services/musicApi';
import { getSongLyrics } from '../services/platforms/kkboxApi';
import { useKkboxStore } from '../store/useKkboxStore';
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
  const foregroundRef = useRef(false);
  const lastSyncRef = useRef(0);
  const lastLocalSkipRef = useRef(0);

  useEffect(() => {
    let prev = usePlayerStore.getState().playbackId;
    const unsub = usePlayerStore.subscribe((state) => {
      if (state.playbackId !== prev) {
        lastLocalSkipRef.current = Date.now();
        prev = state.playbackId;
      }
    });
    return unsub;
  }, []);

  useEffect(() => {
    const timer = setInterval(async () => {
      if (foregroundRef.current) {
        foregroundRef.current = false;
        return;
      }
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

  const onActiveTrackChanged = useCallback(async (event: any) => {
    const trackIndex = event.index;
    if (trackIndex == null || trackIndex < 0) return;
    const { queue, currentIndex } = usePlayerStore.getState();
    if (trackIndex < queue.length && trackIndex !== currentIndex) {
      if (Date.now() - lastLocalSkipRef.current > 3000) {
        usePlayerStore.setState({
          currentIndex: trackIndex,
          currentSong: queue[trackIndex],
          playbackId: usePlayerStore.getState().playbackId + 1,
        });
      }
    }
    try {
      const ps = await NativeModules.TrackPlayerModule.getPlaybackState();
      if (ps?.state) {
        const s = ps.state;
        usePlayerStore.getState().setIsPlaying(
          s === 'playing' || s === 'ready' || s === 'buffering' || s === 'loading'
        );
      }
    } catch {}
  }, []);

  const trackChangeEvents = useMemo(() => [Event.PlaybackActiveTrackChanged], []);
  useTrackPlayerEvents(trackChangeEvents, onActiveTrackChanged);

  const pollSyncFromNative = useCallback(async () => {
    if (Date.now() - lastSyncRef.current < 2000) return;
    try {
      const [idx, playbackState] = await Promise.all([
        NativeModules.TrackPlayerModule.getQueueIndex(),
        NativeModules.TrackPlayerModule.getPlaybackState(),
      ]);
      lastSyncRef.current = Date.now();
      const patch: Record<string, any> = {};
      if (idx != null && idx >= 0) {
        const { queue, currentIndex } = usePlayerStore.getState();
        if (idx < queue.length && idx !== currentIndex) {
          if (Date.now() - lastLocalSkipRef.current > 3000) {
            patch.currentIndex = idx;
            patch.currentSong = queue[idx];
            patch.playbackId = usePlayerStore.getState().playbackId + 1;
          }
        }
      }
      if (playbackState?.state) {
        const s = playbackState.state;
        patch.isPlaying = s === 'playing' || s === 'ready' || s === 'buffering' || s === 'loading';
      }
      if (Object.keys(patch).length) usePlayerStore.setState(patch);
    } catch {}
  }, []);

  useEffect(() => {
    const interval = setInterval(pollSyncFromNative, 5000);
    return () => clearInterval(interval);
  }, [pollSyncFromNative]);

  useEffect(() => {
    const sub = AppState.addEventListener('change', async (state) => {
      if (state !== 'active') return;
      foregroundRef.current = true;
      await pollSyncFromNative();
    });
    return () => sub.remove();
  }, [pollSyncFromNative]);

  useEffect(() => {
    if (!currentSong?.id || currentSong.lyric !== undefined) return;
    const songId = currentSong.id;

    const fetch = currentSong.platform === 'kkbox'
      ? (async () => {
          const rawId = songId.replace(/^kkbox:/, '');
          const acct = useKkboxStore.getState().getActiveAccount();
          if (!acct?.session?.loggedIn) throw new Error('KKBOX not logged in');
          return getSongLyrics(acct.session.sid, rawId);
        })()
      : fetchLyric(songId, currentSong.title, currentSong.artist);

    fetch.then(({ lrc, tlyric }) => {
      if (usePlayerStore.getState().currentSong?.id === songId) {
        usePlayerStore.getState().updateSongLyric(songId, lrc, tlyric ?? undefined);
      }
    }).catch(() => {});
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

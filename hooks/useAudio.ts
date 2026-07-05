import { useCallback } from 'react';
import TrackPlayer from 'react-native-track-player';

type AudioStatus = 'idle' | 'loading' | 'playing' | 'paused' | 'error';

export interface AudioState {
  status: AudioStatus;
  currentTime: number;
  duration: number;
  buffered: number;
  isLoaded: boolean;
}

export interface AudioControls {
  play: () => Promise<void>;
  pause: () => Promise<void>;
  seek: (time: number) => Promise<void>;
  load: (uri: string) => Promise<void>;
  unload: () => Promise<void>;
  setVolume: (volume: number) => Promise<void>;
  togglePlay?: () => void;
  notifyBeforeSeek?: () => void;
  state: AudioState;
}

export function useAudio(): AudioControls {
  const state: AudioState = {
    status: 'idle', currentTime: 0, duration: 0, buffered: 0, isLoaded: false,
  };

  const load = useCallback(async (uri: string) => {
    try {
      await TrackPlayer.load({ id: 'current', url: uri });
    } catch {}
  }, []);

  const play = useCallback(async () => {
    try { await TrackPlayer.play(); } catch {}
  }, []);

  const pause = useCallback(async () => {
    try { await TrackPlayer.pause(); } catch {}
  }, []);

  const seek = useCallback(async (time: number) => {
    try { await TrackPlayer.seekTo(time); } catch {}
  }, []);

  const unload = useCallback(async () => {
    try { await TrackPlayer.reset(); } catch {}
  }, []);

  const setVolume = useCallback(async (volume: number) => {
    try { await TrackPlayer.setVolume(Math.max(0, Math.min(1, volume))); } catch {}
  }, []);

  return { state, load, play, pause, seek, unload, setVolume };
}

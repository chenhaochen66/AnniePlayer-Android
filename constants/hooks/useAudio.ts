import { useRef, useCallback, useEffect, useState } from 'react';

type AudioStatus = 'idle' | 'loading' | 'playing' | 'paused' | 'error';

interface AudioState {
  status: AudioStatus;
  currentTime: number;
  duration: number;
  isLoaded: boolean;
}

export interface AudioControls {
  play: () => Promise<void>;
  pause: () => Promise<void>;
  seek: (time: number) => Promise<void>;
  load: (uri: string) => Promise<void>;
  unload: () => Promise<void>;
  setVolume: (volume: number) => Promise<void>;
  setOnFinish: (cb: (() => void) | null) => void;
  state: AudioState;
}

export function useAudio(): AudioControls {
  const soundRef = useRef<any>(null);
  const uriRef = useRef<string>('');
  const onFinishRef = useRef<(() => void) | null>(null);
  const [state, setState] = useState<AudioState>({
    status: 'idle', currentTime: 0, duration: 0, isLoaded: false,
  });

  const updateStatus = useCallback((status: any) => {
    if (!status.isLoaded) {
      setState((prev) => ({ ...prev, status: 'loading', isLoaded: false }));
      return;
    }
    if (status.didJustFinish) {
      onFinishRef.current?.();
    }
    setState({
      status: status.isPlaying ? 'playing' : 'paused',
      currentTime: status.positionMillis / 1000,
      duration: (status.durationMillis || 0) / 1000,
      isLoaded: true,
    });
  }, []);

  const load = useCallback(async (uri: string) => {
    if (uri === uriRef.current && soundRef.current) return;
    uriRef.current = uri;
    setState((prev) => ({ ...prev, status: 'loading', isLoaded: false }));

    if (soundRef.current) {
      await soundRef.current.unloadAsync().catch(() => {});
      soundRef.current = null;
    }

    const Audio = require('expo-av').Audio;
    await Audio.setAudioModeAsync({
      playsInSilentMode: true,
      shouldPlayInBackground: true,
      shouldDuckAndroid: true,
      staysActiveInBackground: true,
    }).catch(() => {});

    const { sound, status } = await Audio.Sound.createAsync(
      { uri },
      { progressUpdateIntervalMillis: 250, shouldPlay: false },
      updateStatus,
    );
    soundRef.current = sound;
    updateStatus(status);
  }, [updateStatus]);

  const play = useCallback(async () => {
    if (soundRef.current) {
      await soundRef.current.playAsync();
    }
  }, []);

  const pause = useCallback(async () => {
    if (soundRef.current) {
      await soundRef.current.pauseAsync();
    }
  }, []);

  const seek = useCallback(async (time: number) => {
    if (soundRef.current) {
      await soundRef.current.setPositionAsync(time * 1000);
      setState((prev) => ({ ...prev, currentTime: time }));
    }
  }, []);

  const unload = useCallback(async () => {
    if (soundRef.current) {
      await soundRef.current.unloadAsync().catch(() => {});
      soundRef.current = null;
    }
    setState({ status: 'idle', currentTime: 0, duration: 0, isLoaded: false });
  }, []);

  const setVolume = useCallback(async (volume: number) => {
    const vol = Math.max(0, Math.min(1, volume));
    if (soundRef.current) {
      await soundRef.current.setVolumeAsync(vol);
    }
  }, []);

  const setOnFinish = useCallback((cb: (() => void) | null) => {
    onFinishRef.current = cb;
  }, []);

  useEffect(() => {
    return () => {
      if (soundRef.current) {
        soundRef.current.unloadAsync().catch(() => {});
      }
    };
  }, []);

  return { state, play, pause, seek, load, unload, setVolume, setOnFinish };
}
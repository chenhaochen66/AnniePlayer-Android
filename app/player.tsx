import React, { useEffect, useCallback } from 'react';
import { Platform } from 'react-native';
import * as NavigationBar from 'expo-navigation-bar';
import { usePlayerStore } from '../store/usePlayerStore';
import { useSharedAudio } from '../contexts/AudioContext';
import { useColorExtractor } from '../hooks/useColorExtractor';
import { useColorStore } from '../store/useColorStore';
import { useKeyboardControls } from '../hooks/useKeyboardControls';
import { useBackHandler } from '../hooks/useBackHandler';
import { FullPlayer } from '../components/FullPlayer';

export default function PlayerScreen() {
  const currentSong = usePlayerStore((s) => s.currentSong);
  const volume = usePlayerStore((s) => s.volume);
  const setVolume = usePlayerStore((s) => s.setVolume);
  const next = usePlayerStore((s) => s.next);
  const previous = usePlayerStore((s) => s.previous);

  const { play, pause, state } = useSharedAudio();
  const { extractColors } = useColorExtractor();

  useBackHandler();

  useEffect(() => {
    if (Platform.OS === 'android') {
      NavigationBar.setButtonStyleAsync('light').catch(() => {});
    }
  }, []);

  useEffect(() => {
    if (currentSong?.cover) {
      extractColors(currentSong.cover).then((colors) => {
        if (colors.length >= 2) {
          useColorStore.getState().setColors({ primary: colors[0], secondary: colors[1] });
        }
      }).catch(() => {});
    }
  }, [currentSong?.cover]);

  const handlePlayPause = useCallback(() => {
    if (state.status === 'playing') {
      pause().catch(() => {});
    } else {
      play().catch(() => {});
    }
  }, [state.status, play, pause]);

  const handleVolumeUp = useCallback(() => {
    const v = Math.min(1, volume + 0.1);
    setVolume(v);
  }, [volume, setVolume]);

  const handleVolumeDown = useCallback(() => {
    const v = Math.max(0, volume - 0.1);
    setVolume(v);
  }, [volume, setVolume]);

  useKeyboardControls({
    onPlayPause: handlePlayPause,
    onNext: next,
    onPrevious: previous,
    onVolumeUp: handleVolumeUp,
    onVolumeDown: handleVolumeDown,
  });

  useEffect(() => {
    if (Platform.OS === 'web' && currentSong) {
      try {
        navigator.mediaSession.metadata = new MediaMetadata({
          title: currentSong.title,
          artist: currentSong.artist,
          artwork: [{ src: currentSong.cover, sizes: '400x400', type: 'image/jpeg' }],
        });
        navigator.mediaSession.setActionHandler('play', () => play().catch(() => {}));
        navigator.mediaSession.setActionHandler('pause', () => pause().catch(() => {}));
        navigator.mediaSession.setActionHandler('nexttrack', () => next());
        navigator.mediaSession.setActionHandler('previoustrack', () => previous());
      } catch {}
    }
  }, [currentSong]);

  return <FullPlayer />;
}

import React, { useEffect, useCallback } from 'react';
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

  const { play, pause } = useSharedAudio();
  const { extractColors } = useColorExtractor();

  useBackHandler();

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
    if (usePlayerStore.getState().isPlaying) {
      pause().catch(() => {});
    } else {
      play().catch(() => {});
    }
  }, [play, pause]);

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
    onNext: () => next(true),
    onPrevious: previous,
    onVolumeUp: handleVolumeUp,
    onVolumeDown: handleVolumeDown,
  });

  return <FullPlayer />;
}

import { useEffect, useCallback } from 'react';
import { Platform } from 'react-native';

interface KeyboardControlHandlers {
  onPlayPause: () => void;
  onNext: () => void;
  onPrevious: () => void;
  onVolumeUp: () => void;
  onVolumeDown: () => void;
}

export function useKeyboardControls(handlers: KeyboardControlHandlers): void {
  const handleKeyDown = useCallback(
    (event: KeyboardEvent) => {
      switch (event.code) {
        case 'Space':
          event.preventDefault();
          handlers.onPlayPause();
          break;
        case 'ArrowRight':
          handlers.onNext();
          break;
        case 'ArrowLeft':
          handlers.onPrevious();
          break;
        case 'ArrowUp':
          event.preventDefault();
          handlers.onVolumeUp();
          break;
        case 'ArrowDown':
          event.preventDefault();
          handlers.onVolumeDown();
          break;
      }
    },
    [handlers],
  );

  useEffect(() => {
    if (Platform.OS !== 'web') return;
    window.addEventListener('keydown', handleKeyDown);
    return () => {
      window.removeEventListener('keydown', handleKeyDown);
    };
  }, [handleKeyDown]);
}

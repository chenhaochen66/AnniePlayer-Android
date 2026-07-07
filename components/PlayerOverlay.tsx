import React, { useEffect, useCallback } from 'react';
import { Dimensions, BackHandler } from 'react-native';
import Animated, { useSharedValue, useAnimatedStyle, withTiming } from 'react-native-reanimated';
import { useUIStore } from '../store/useUIStore';
import { FullPlayer } from './FullPlayer';

const SCREEN_H = Dimensions.get('window').height;

export function PlayerOverlay() {
  const visible = useUIStore((s) => s.playerOverlayVisible);
  const setVisible = useUIStore((s) => s.setPlayerOverlayVisible);
  const translateY = useSharedValue(SCREEN_H);
  const onClose = useCallback(() => setVisible(false), [setVisible]);

  useEffect(() => {
    if (!visible) return;
    const sub = BackHandler.addEventListener('hardwareBackPress', () => {
      if (useUIStore.getState().queueVisible) {
        useUIStore.getState().setQueueVisible(false);
        return true;
      }
      setVisible(false);
      return true;
    });
    return () => sub.remove();
  }, [visible, setVisible]);

  useEffect(() => {
    translateY.value = withTiming(visible ? 0 : SCREEN_H, { duration: 150 });
  }, [visible]);

  const animatedStyle = useAnimatedStyle(() => ({
    transform: [{ translateY: translateY.value }],
  }));

  return (
    <Animated.View
      pointerEvents={visible ? 'auto' : 'none'}
      style={[{
        position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, zIndex: 300,
        backgroundColor: '#0a0a1a',
      }, animatedStyle]}
    >
      {visible && <FullPlayer onClose={onClose} />}
    </Animated.View>
  );
}

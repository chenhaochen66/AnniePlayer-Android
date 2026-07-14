import React, { useEffect, useCallback } from 'react';
import { useWindowDimensions, BackHandler } from 'react-native';
import Animated, { useSharedValue, useAnimatedStyle, withTiming, Easing } from 'react-native-reanimated';
import { useUIStore } from '../store/useUIStore';
import { FullPlayer } from './FullPlayer';

export function PlayerOverlay() {
  const { height: winH } = useWindowDimensions();
  const visible = useUIStore((s) => s.playerOverlayVisible);
  const setVisible = useUIStore((s) => s.setPlayerOverlayVisible);
  const onClose = useCallback(() => setVisible(false), [setVisible]);

  const translateY = useSharedValue(winH);
  const opacity = useSharedValue(0);

  useEffect(() => {
    const toValue = visible ? 0 : winH;
    const animConfig = { duration: 200, easing: Easing.bezier(0.25, 0.1, 0.25, 1) };
    translateY.value = withTiming(toValue, animConfig);
    opacity.value = withTiming(visible ? 1 : 0, { duration: 150 });
  }, [visible, winH, translateY, opacity]);

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

  const animStyle = useAnimatedStyle(() => ({
    transform: [{ translateY: translateY.value }],
    opacity: opacity.value,
  }));

  return (
    <Animated.View
      style={[{
        position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, zIndex: 300,
        backgroundColor: '#0a0a1a',
      }, animStyle]}
      pointerEvents={visible ? 'auto' : 'none'}
    >
      <FullPlayer onClose={onClose} />
    </Animated.View>
  );
}

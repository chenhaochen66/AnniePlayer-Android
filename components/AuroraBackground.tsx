import React, { useEffect, useRef } from 'react';
import { Platform, StyleSheet } from 'react-native';
import Animated, {
  useAnimatedStyle,
  useSharedValue,
  withRepeat,
  withTiming,
  interpolate,
} from 'react-native-reanimated';
import { useColorStore } from '../store/useColorStore';

const isWeb = Platform.OS === 'web';

function AuroraLayer({
  color,
  offsetX,
  offsetY,
  duration,
  size,
}: {
  color: string;
  offsetX: number;
  offsetY: number;
  duration: number;
  size: number;
}) {
  const translateX = useSharedValue(0);
  const translateY = useSharedValue(0);

  useEffect(() => {
    translateX.value = withRepeat(
      withTiming(1, { duration }),
      -1,
      true,
    );
    translateY.value = withRepeat(
      withTiming(1, { duration: duration * 1.3 }),
      -1,
      true,
    );
  }, [duration]);

  const animatedStyle = useAnimatedStyle(() => ({
    transform: [
      { translateX: interpolate(translateX.value, [0, 1], [-offsetX, offsetX]) },
      { translateY: interpolate(translateY.value, [0, 1], [-offsetY, offsetY]) },
    ],
  }));

  if (isWeb) {
    return null;
  }

  return (
    <Animated.View
      style={[
        {
          position: 'absolute',
          width: size,
          height: size,
          borderRadius: size / 2,
          backgroundColor: color,
          opacity: 0.15 + Math.random() * 0.1,
          top: '50%',
          left: '50%',
          marginLeft: -size / 2,
          marginTop: -size / 2 + offsetY * 0.5,
        },
        animatedStyle,
      ]}
      pointerEvents="none"
    />
  );
}

export const AuroraBackground = React.memo(() => {
  const palette = useColorStore((s) => s.colors);

  const webStyle = isWeb
    ? ({
        position: 'fixed',
        top: 0,
        left: 0,
        right: 0,
        bottom: 0,
        zIndex: -1,
        overflow: 'hidden',
        background: `
          radial-gradient(ellipse at 20% 50%, ${palette.primary}33 0%, transparent 50%),
          radial-gradient(ellipse at 80% 20%, ${palette.secondary}22 0%, transparent 50%),
          radial-gradient(ellipse at 50% 80%, ${palette.primary}11 0%, transparent 50%)
        `,
        animation: 'auroraDrift 20s ease-in-out infinite alternate',
      } as const)
    : ({} as const);

  if (isWeb) {
    return (
      <div style={webStyle}>
        <style>{`
          @keyframes auroraDrift {
            0% { transform: translate(0, 0) scale(1); }
            25% { transform: translate(2%, -1%) scale(1.05); }
            50% { transform: translate(-1%, 2%) scale(0.95); }
            75% { transform: translate(1%, 1%) scale(1.02); }
            100% { transform: translate(-2%, -1%) scale(1); }
          }
        `}</style>
      </div>
    );
  }

  return (
    <Animated.View style={StyleSheet.absoluteFill} pointerEvents="none">
      <AuroraLayer
        color={palette.primary}
        offsetX={60}
        offsetY={40}
        duration={8000}
        size={500}
      />
      <AuroraLayer
        color={palette.secondary}
        offsetX={80}
        offsetY={60}
        duration={12000}
        size={400}
      />
      <AuroraLayer
        color={palette.background}
        offsetX={50}
        offsetY={30}
        duration={10000}
        size={600}
      />
    </Animated.View>
  );
});

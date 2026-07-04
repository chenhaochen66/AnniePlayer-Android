import React, { useEffect } from 'react';
import { Platform, View } from 'react-native';
import Animated, {
  useAnimatedStyle,
  useSharedValue,
  withRepeat,
  withTiming,
  Easing,
} from 'react-native-reanimated';

const isWeb = Platform.OS === 'web';

function SkeletonBlock({ width, height, style }: { width: number | string; height: number; style?: object }) {
  const opacity = useSharedValue(0.3);

  useEffect(() => {
    opacity.value = withRepeat(
      withTiming(0.7, { duration: 1000, easing: Easing.inOut(Easing.ease) }),
      -1,
      true,
    );
  }, []);

  const animatedStyle = useAnimatedStyle(() => ({
    opacity: opacity.value,
  }));

  if (isWeb) {
    return (
      <View
        style={[
          {
            width: width as any,
            height,
            backgroundColor: 'rgba(255,255,255,0.1)',
            borderRadius: 8,
            animation: 'skeletonPulse 1.5s ease-in-out infinite',
          } as any,
          style,
        ]}
      />
    );
  }

  return (
    <Animated.View
      style={[
        {
          width: width as any,
          height,
          backgroundColor: 'rgba(255,255,255,0.1)',
          borderRadius: 8,
        },
        animatedStyle,
        style,
      ]}
    />
  );
}

function SkeletonColumn() {
  return (
    <View style={{ flex: 1, gap: 4 }}>
      <SkeletonBlock width={60} height={14} style={{ marginLeft: 8, marginBottom: 4 }} />
      {[1, 2, 3, 4].map((i) => (
        <View
          key={i}
          style={{ flexDirection: 'row', alignItems: 'center', gap: 8, padding: 8 }}
        >
          <SkeletonBlock width={36} height={36} style={{ borderRadius: 6 }} />
          <View style={{ flex: 1, gap: 4 }}>
            <SkeletonBlock width="80%" height={12} />
            <SkeletonBlock width="50%" height={10} />
          </View>
          <SkeletonBlock width={16} height={16} style={{ borderRadius: 8 }} />
        </View>
      ))}
    </View>
  );
}

export const SkeletonLoader = React.memo(() => {
  const webStyles = isWeb ? (
    <style>{`
      @keyframes skeletonPulse {
        0%, 100% { opacity: 0.3; }
        50% { opacity: 0.7; }
      }
    `}</style>
  ) : null;

  return (
    <>
      {webStyles}
      <View style={{ flex: 1, flexDirection: 'row', paddingHorizontal: 8, paddingTop: 8 }}>
        <SkeletonColumn />
        <View style={{ width: 1, backgroundColor: 'rgba(255,255,255,0.04)', marginVertical: 8 }} />
        <SkeletonColumn />
      </View>
    </>
  );
});

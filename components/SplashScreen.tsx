import { useEffect, useRef } from 'react';
import { Animated, Image, StyleSheet, View, Dimensions } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

const { width, height } = Dimensions.get('window');
const COLORS = ['#FF6B6B', '#FFE66D', '#48DBFB', '#FF9FF3', '#54A0FF', '#5F27CD'];

interface SplashScreenProps {
  onPrepare?: () => void;
  onFinish: () => void;
}

export default function SplashScreen({ onPrepare, onFinish }: SplashScreenProps) {
  const opacity = useRef(new Animated.Value(0)).current;
  const insets = useSafeAreaInsets();

  useEffect(() => {
    Animated.timing(opacity, {
      toValue: 1,
      duration: 500,
      useNativeDriver: true,
    }).start();

    const prepareTimer = setTimeout(() => {
      onPrepare?.();
    }, 1000);

    const fadeTimer = setTimeout(() => {
      Animated.timing(opacity, {
        toValue: 0,
        duration: 500,
        useNativeDriver: true,
      }).start(() => {
        onFinish();
      });
    }, 1500);

    return () => {
      clearTimeout(prepareTimer);
      clearTimeout(fadeTimer);
    };
  }, []);

  const chars = '安妮音乐盒'.split('');

  return (
    <Animated.View
      style={[
        styles.container,
        {
          paddingTop: insets.top,
          paddingBottom: insets.bottom,
          opacity,
        },
      ]}
      pointerEvents="auto"
    >
      <View style={styles.content}>
        <Image
          source={require('../assets/annie.jpg')}
          style={styles.image}
          resizeMode="contain"
        />
        <View style={styles.textRow}>
          {chars.map((char, i) => (
            <Animated.Text key={i} style={[styles.char, { color: COLORS[i % COLORS.length] }]}>
              {char}
            </Animated.Text>
          ))}
        </View>
      </View>
    </Animated.View>
  );
}

const styles = StyleSheet.create({
  container: {
    ...StyleSheet.absoluteFillObject,
    backgroundColor: '#1a1a2e',
    zIndex: 9999,
    justifyContent: 'center',
    alignItems: 'center',
  },
  content: {
    alignItems: 'center',
    justifyContent: 'center',
  },
  image: {
    width: width * 0.45,
    height: width * 0.45,
    borderRadius: 20,
  },
  textRow: {
    flexDirection: 'row',
    marginTop: 24,
  },
  char: {
    fontSize: 32,
    fontWeight: 'bold',
    fontFamily: 'Georgia',
    fontStyle: 'italic',
    letterSpacing: 2,
    textShadowColor: 'rgba(255,255,255,0.3)',
    textShadowOffset: { width: 0, height: 0 },
    textShadowRadius: 10,
  },
});

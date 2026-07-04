import React, { useCallback, useRef, useEffect, useMemo, useState } from 'react';
import {
  View,
  Text,
  FlatList,
  TouchableOpacity,
  Dimensions,
  Platform,
} from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import Animated, {
  useAnimatedStyle,
  useSharedValue,
  withTiming,
} from 'react-native-reanimated';
import { usePlayerStore } from '../store/usePlayerStore';
import { useColorStore } from '../store/useColorStore';
import { parseLrc, LyricLine, findCurrentLineIndex } from '../utils/lrcParser';

const { height: SCREEN_HEIGHT } = Dimensions.get('window');
const TARGET_LINE_Y = SCREEN_HEIGHT * 0.235;

const LyricItem = React.memo(
  ({ item, index, activeIndex, primaryColor, onMeasure }: { item: LyricLine; index: number; activeIndex: number; primaryColor: string; onMeasure?: (index: number, height: number) => void }) => {
    const isActive = index === activeIndex;
    const isPast = index < activeIndex;
    return (
      <View
        onLayout={(e) => onMeasure?.(index, e.nativeEvent.layout.height)}
        style={{ paddingVertical: 12, justifyContent: 'center', alignItems: 'center', paddingHorizontal: 24 }}
      >
        <Text
          style={{
            fontSize: isActive ? 20 : 15,
            fontWeight: isActive ? '700' : '400',
            color: isActive ? '#fff' : isPast ? 'rgba(255,255,255,0.3)' : 'rgba(255,255,255,0.5)',
            textAlign: 'center',
            textShadowColor: isActive ? primaryColor : 'transparent',
            textShadowRadius: isActive ? 20 : 0,
          }}
        >
          {item.text}
        </Text>
      </View>
    );
  },
  (prev, next) =>
    prev.index === next.index &&
    prev.activeIndex === next.activeIndex &&
    prev.primaryColor === next.primaryColor,
);

export const LyricsModal = React.memo(() => {
  const isVisible = usePlayerStore((s) => s.isLyricsVisible);
  const setLyricsVisible = usePlayerStore((s) => s.setLyricsVisible);
  const currentSong = usePlayerStore((s) => s.currentSong);
  const currentTime = usePlayerStore((s) => s.currentTime);
  const palette = useColorStore((s) => s.colors);
  const [flatListReady, setFlatListReady] = useState(false);
  const [scrollGeneration, setScrollGeneration] = useState(0);
  const flatListRef = useRef<FlatList>(null);
  const translateY = useSharedValue(SCREEN_HEIGHT);
  const userScrolling = useRef(false);
  const measuredHeightsRef = useRef<number[]>([]);

  const lyrics = useMemo(() => {
    if (!currentSong?.lyric) return [];
    return parseLrc(currentSong.lyric);
  }, [currentSong?.lyric]);

  const currentLineIndex = useMemo(
    () => findCurrentLineIndex(lyrics, currentTime),
    [lyrics, currentTime],
  );

  useEffect(() => {
    translateY.value = withTiming(isVisible ? 0 : SCREEN_HEIGHT, { duration: 200 });
  }, [isVisible]);

  const animatedStyle = useAnimatedStyle(() => ({
    transform: [{ translateY: translateY.value }],
  }));

  const handleMeasure = useCallback((index: number, height: number) => {
    measuredHeightsRef.current[index] = height;
  }, []);

  useEffect(() => {
    setFlatListReady(false);
    measuredHeightsRef.current = [];
    userScrolling.current = false;
  }, [currentSong?.id]);

  useEffect(() => {
    if (currentLineIndex >= 0 && !userScrolling.current && flatListReady) {
      requestAnimationFrame(() => {
        let offset = SCREEN_HEIGHT * 0.3;
        for (let i = 0; i < currentLineIndex; i++) {
          offset += measuredHeightsRef.current[i] ?? 48;
        }
        flatListRef.current?.scrollToOffset({ offset: Math.max(0, offset - TARGET_LINE_Y), animated: true });
      });
    }
  }, [currentLineIndex, flatListReady, scrollGeneration]);

  const handleClose = useCallback(() => {
    setLyricsVisible(false);
  }, [setLyricsVisible]);

  const handleScrollBegin = useCallback(() => {
    userScrolling.current = true;
  }, []);

  const handleScrollEnd = useCallback(() => {
    setTimeout(() => {
      userScrolling.current = false;
      setScrollGeneration((g) => g + 1);
    }, 500);
  }, []);

  const renderItem = useCallback(
    ({ item, index }: { item: LyricLine; index: number }) => (
      <LyricItem item={item} index={index} activeIndex={currentLineIndex} primaryColor={palette.primary} onMeasure={handleMeasure} />
    ),
    [currentLineIndex, palette.primary, handleMeasure],
  );

  if (!isVisible) return null;

  return (
    <Animated.View
      style={[
        {
          position: 'absolute',
          top: 0,
          left: 0,
          right: 0,
          bottom: 0,
          backgroundColor: 'rgba(0,0,0,0.35)',
          zIndex: 100,
        },
        animatedStyle,
      ]}
    >
      <TouchableOpacity
        onPress={handleClose}
        activeOpacity={0.7}
        style={{
          position: 'absolute',
          top: Platform.OS === 'ios' ? 60 : 40,
          right: 16,
          zIndex: 10,
          padding: 8,
        }}
      >
        <Ionicons name="close" size={28} color="#fff" />
      </TouchableOpacity>

      {lyrics.length > 0 ? (
        <FlatList
          key={currentSong?.id}
          ref={flatListRef}
          data={lyrics}
          renderItem={renderItem}
          keyExtractor={(_, index) => String(index)}
          onScrollBeginDrag={handleScrollBegin}
          onScrollEndDrag={handleScrollEnd}
          onMomentumScrollBegin={handleScrollBegin}
          onMomentumScrollEnd={handleScrollEnd}
          onLayout={() => setFlatListReady(true)}
          nestedScrollEnabled
          contentContainerStyle={{
            flexGrow: 1,
            paddingVertical: SCREEN_HEIGHT * 0.3,
          }}
          showsVerticalScrollIndicator={false}
        />
      ) : (
        <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center' }}>
          <Text style={{ color: 'rgba(255,255,255,0.5)', fontSize: 16 }}>
            暂无歌词
          </Text>
        </View>
      )}
    </Animated.View>
  );
});

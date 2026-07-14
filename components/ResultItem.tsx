import React, { useCallback, useMemo, useState } from 'react';
import { View, Text, Image, TouchableOpacity, Vibration } from 'react-native';
import { GestureDetector, Gesture, TouchableOpacity as RNGHTouchableOpacity } from 'react-native-gesture-handler';
import Animated, { useSharedValue, useAnimatedStyle, withTiming, interpolate, runOnJS } from 'react-native-reanimated';
import { Ionicons } from '@expo/vector-icons';
import { Song } from '../types';
import { useDownloadStore } from '../store/useDownloadStore';

interface ResultItemProps {
  song: Song;
  isActive: boolean;
  onPress: (song: Song) => void;
  onDownload: (song: Song) => void;
  onToggleSelect?: (song: Song) => void;
  selected?: boolean;
}

export const ResultItem = React.memo(
  ({ song, isActive, onPress, onDownload, onToggleSelect, selected }: ResultItemProps) => {
    const [imgError, setImgError] = useState(false);
    const downloadTask = useDownloadStore((s) => s.tasks[song.id]);

    const isDownloading = downloadTask?.status === 'downloading' || downloadTask?.status === 'pending';
    const swipeX = useSharedValue(0);

    const handleSwipe = useCallback(() => {
      Vibration.vibrate(40);
      onToggleSelect?.(song);
    }, [song, onToggleSelect]);

    const animatedStyle = useAnimatedStyle(() => ({
      transform: [{ translateX: interpolate(swipeX.value, [0, 60], [0, 8]) }],
    }));

    const overlayStyle = useAnimatedStyle(() => ({
      opacity: interpolate(swipeX.value, [0, 50], [0, 0.35]),
    }));

    const tap = useMemo(() => Gesture.Tap().onEnd(() => runOnJS(onPress)(song)), [song, onPress]);
    const swipe = useMemo(
      () => Gesture.Pan()
        .activeOffsetX(30)
        .failOffsetY(10)
        .onUpdate((e) => { swipeX.value = e.translationX; })
        .onEnd((e) => {
          if (e.translationX > 30) {
            runOnJS(handleSwipe)();
          }
          swipeX.value = withTiming(0, { duration: 200 });
        }),
      [handleSwipe],
    );
    const composed = useMemo(() => Gesture.Exclusive(swipe, tap), [swipe, tap]);

    return (
      <View style={{ flexDirection: 'row', alignItems: 'center', paddingVertical: 4, paddingHorizontal: 4 }}>
        <GestureDetector gesture={composed} style={{ flex: 1 }}>
          <Animated.View
            style={[{
              flex: 1,
              flexDirection: 'row',
              alignItems: 'center',
              paddingVertical: 4,
              paddingHorizontal: 4,
              backgroundColor: selected
                ? 'rgba(129,140,248,0.08)'
                : isActive
                  ? 'rgba(129,140,248,0.06)'
                  : 'transparent',
              borderRadius: 8,
              overflow: 'hidden',
            }, animatedStyle]}
          >
            <Animated.View
              pointerEvents="none"
              style={[{
                position: 'absolute', top: 0, left: 0, right: 0, bottom: 0,
                backgroundColor: '#818cf8', borderRadius: 8,
              }, overlayStyle]}
            />
            {selected && (
              <Ionicons
                name="checkbox"
                size={18}
                color="#818cf8"
                style={{ marginRight: 8 }}
              />
            )}
            <View style={{ width: 44, height: 44, borderRadius: 8, backgroundColor: 'rgba(255,255,255,0.03)', overflow: 'hidden', alignItems: 'center', justifyContent: 'center' }}>
              {imgError || !song.cover ? (
                <Ionicons name="musical-note" size={18} color="rgba(255,255,255,0.15)" />
              ) : (
                <Image
                  source={{ uri: song.cover }}
                  style={{ width: 44, height: 44, borderRadius: 8, opacity: isActive ? 0.6 : 1 }}
                  onError={() => setImgError(true)}
                />
              )}
              {isActive && (
                <View style={{ position: 'absolute', width: 44, height: 44, alignItems: 'center', justifyContent: 'center' }}>
                  <Ionicons name="play" size={16} color="#fff" />
                </View>
              )}
            </View>
            <View style={{ flex: 1, marginLeft: 10, marginRight: 4 }}>
              <Text
                style={{
                  color: isActive ? '#818cf8' : '#fff',
                  fontSize: 14,
                  fontWeight: '600',
                  marginBottom: 2,
                }}
                numberOfLines={1}
              >
                {song.title}
              </Text>
              <Text style={{ color: 'rgba(255,255,255,0.35)', fontSize: 12 }} numberOfLines={1}>
                {song.artist}
              </Text>
            </View>
          </Animated.View>
        </GestureDetector>
        <TouchableOpacity onPress={() => onDownload(song)} activeOpacity={0.7} style={{ padding: 6 }}>
          {isDownloading ? (
            <View style={{
              width: 16, height: 16, borderRadius: 8,
              borderWidth: 2,
              borderColor: 'rgba(99,102,241,0.2)',
              borderTopColor: '#818cf8',
            }} />
          ) : (
            <Ionicons name="download-outline" size={18} color="rgba(255,255,255,0.5)" />
          )}
        </TouchableOpacity>
      </View>
    );
  },
  (prev, next) =>
    prev.song.id === next.song.id &&
    prev.isActive === next.isActive &&
    prev.selected === next.selected,
);

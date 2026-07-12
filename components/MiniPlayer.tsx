import React, { useCallback, useState, useEffect, useRef, memo } from 'react';
import { useSegments } from 'expo-router';
import {
  View,
  Text,
  Image,
  TouchableOpacity,
} from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import Animated, {
  useAnimatedStyle,
  useSharedValue,
  withTiming,
} from 'react-native-reanimated';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { usePlayerStore } from '../store/usePlayerStore';
import { useUIStore } from '../store/useUIStore';
import { useSharedAudio } from '../contexts/AudioContext';
import { MovingText } from './MovingText';

const HIDDEN_TRANSLATE_Y = 120;

const MiniProgressBar = memo(() => {
  const currentTime = usePlayerStore((s) => s.currentTime);
  const duration = usePlayerStore((s) => s.duration);
  const progress = duration > 0 ? (currentTime / duration) * 100 : 0;
  return (
    <View style={{
      position: 'absolute',
      bottom: 0, left: 0, height: 4,
      width: `${progress}%`,
      backgroundColor: 'rgba(255,255,255,0.25)',
    }} />
  );
});

export const MiniPlayer = React.memo(() => {
  const insets = useSafeAreaInsets();
  const segments = useSegments();
  const currentSong = usePlayerStore((s) => s.currentSong);
  const isPlaying = usePlayerStore((s) => s.isPlaying);
  const togglePlay = useSharedAudio().togglePlay!;
  const playerOverlayVisible = useUIStore((s) => s.playerOverlayVisible);
  const setPlayerOverlayVisible = useUIStore((s) => s.setPlayerOverlayVisible);
  const [coverError, setCoverError] = useState(false);
  const lastSongRef = useRef(currentSong);
  if (currentSong) lastSongRef.current = currentSong;

  const hasSong = !!currentSong;
  const isVisible = hasSong && segments[0] !== 'player' && !playerOverlayVisible;
  const translateY = useSharedValue(isVisible ? 0 : HIDDEN_TRANSLATE_Y);

  useEffect(() => {
    translateY.value = isVisible ? withTiming(0, { duration: 200 }) : HIDDEN_TRANSLATE_Y;
  }, [isVisible]);

  useEffect(() => {
    setCoverError(false);
  }, [currentSong?.id]);

  const containerStyle = useAnimatedStyle(() => ({
    transform: [{ translateY: translateY.value }],
  }));

  const handlePress = useCallback(() => setPlayerOverlayVisible(true), [setPlayerOverlayVisible]);
  const handlePlayPause = useCallback(
    (e: any) => { e.stopPropagation(); togglePlay(); },
    [togglePlay],
  );

  const song = currentSong || lastSongRef.current;
  if (!song) return null;

  return (
    <Animated.View
      pointerEvents={isVisible ? 'auto' : 'none'}
      style={[{
        position: 'absolute',
        bottom: 0,
        left: 0,
        right: 0,
        backgroundColor: '#0a0a1a',
        paddingTop: 8,
      }, containerStyle]}
    >
      <TouchableOpacity
        onPress={handlePress}
        activeOpacity={0.95}
        style={{
          flexDirection: 'row',
          alignItems: 'center',
          paddingHorizontal: 14,
          paddingVertical: 12,
          paddingBottom: 12 + (insets.bottom || 12),
          backgroundColor: 'rgba(28,28,48,0.94)',
          borderRadius: 0,
          shadowColor: '#000',
          shadowOffset: { width: 0, height: -4 },
          shadowOpacity: 0.3,
          shadowRadius: 16,
          elevation: 14,
        }}
      >
        
        <MiniProgressBar />

        {coverError ? (
          <View style={{
            width: 52, height: 52, borderRadius: 12,
            backgroundColor: 'rgba(255,255,255,0.04)',
            alignItems: 'center', justifyContent: 'center',
          }}>
            <Ionicons name="musical-note" size={22} color="rgba(255,255,255,0.2)" />
          </View>
        ) : (
          <Image
            source={{ uri: song.cover }}
            style={{
              width: 52, height: 52, borderRadius: 12,
              backgroundColor: 'rgba(255,255,255,0.04)',
            }}
            onError={() => setCoverError(true)}
          />
        )}

        <View style={{ flex: 1, marginLeft: 12, overflow: 'hidden' }}>
          <MovingText
            text={song.title}
            threshold={15}
            style={{ color: '#fff', fontSize: 16, fontWeight: '600', marginBottom: 3 }}
          />
          <Text style={{ color: 'rgba(255,255,255,0.4)', fontSize: 13 }} numberOfLines={1}>
            {song.artist}
          </Text>
        </View>

        <TouchableOpacity
          onPress={handlePlayPause}
          activeOpacity={0.7}
          style={{
            width: 44,
            height: 44,
            borderRadius: 22,
            backgroundColor: 'rgba(255,255,255,0.08)',
            alignItems: 'center',
            justifyContent: 'center',
            marginLeft: 10,
          }}
        >
          <Ionicons
            name={isPlaying ? 'pause' : 'play'}
            size={20}
            color="#fff"
            style={{ marginLeft: isPlaying ? 0 : 2 }}
          />
        </TouchableOpacity>
      </TouchableOpacity>
    </Animated.View>
  );
});

export const MiniPlayerWrapper = memo(() => {
  return <MiniPlayer />;
});

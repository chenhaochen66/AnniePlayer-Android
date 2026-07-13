import React, { useState, useEffect, useRef } from 'react';
import { StatusBar, View, Alert, Platform } from 'react-native';
import { Stack } from 'expo-router';
import * as NavigationBar from 'expo-navigation-bar';
import { useUpdates } from 'expo-updates';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import { QueryClientProvider } from '@tanstack/react-query';
import { SafeAreaProvider, useSafeAreaInsets } from 'react-native-safe-area-context';
import Toast from 'react-native-toast-message';
import TrackPlayer, { Capability, AppKilledPlaybackBehavior } from 'react-native-track-player';
import { queryClient } from '../services/queryClient';
import { PlaybackService } from '../services/playbackService';
import { colors } from '../constants/tokens';
import { AuroraBackground } from '../components/AuroraBackground';
import { MiniPlayerWrapper } from '../components/MiniPlayer';
import { LyricsModal } from '../components/LyricsModal';
import { PlayerOverlay } from '../components/PlayerOverlay';
import { AudioProvider } from '../contexts/AudioContext';
import { PlayerProgressProvider } from '../contexts/PlayerProgressContext';
import SplashScreen from '../components/SplashScreen';
import { ContactModal } from '../components/ContactModal';
import { SearchSelectionOverlay } from '../components/SearchSelectionOverlay';
import { SingerSelectionOverlay } from '../components/SingerSelectionOverlay';

TrackPlayer.registerPlaybackService(() => PlaybackService);

export default function RootLayout() {
  const insets = useSafeAreaInsets();
  const [showMain, setShowMain] = useState(false);
  const [splashDone, setSplashDone] = useState(false);
  const { isUpdatePending, downloadedUpdate } = useUpdates();
  const alertedRef = useRef(false);

  useEffect(() => {
    if (Platform.OS === 'android') {
      NavigationBar.setPositionAsync('absolute').catch(() => {});
      NavigationBar.setBackgroundColorAsync('#0a0a1a').catch(() => {});
      NavigationBar.setButtonStyleAsync('light').catch(() => {});
      NavigationBar.setBehaviorAsync('overlay-swipe').catch(() => {});
    }
  }, []);

  useEffect(() => {
    TrackPlayer.setupPlayer({}).then(() => {
      TrackPlayer.updateOptions({
        android: { appKilledPlaybackBehavior: AppKilledPlaybackBehavior.ContinuePlayback },
        capabilities: [Capability.Play, Capability.Pause, Capability.SkipToNext, Capability.SkipToPrevious, Capability.SeekTo],
        compactCapabilities: [Capability.Play, Capability.Pause, Capability.SkipToNext],
      }).catch(() => {});
    }).catch(() => {});
    return () => { TrackPlayer.reset().catch(() => {}); };
  }, []);

  useEffect(() => {
    if (isUpdatePending && !alertedRef.current && downloadedUpdate) {
      alertedRef.current = true;
      const time = downloadedUpdate.createdAt?.toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }) ?? '';
      Alert.alert('更新已下载', `发布于 ${time}，重启后生效`);
    }
  }, [isUpdatePending, downloadedUpdate]);

  return (
    <GestureHandlerRootView style={{ flex: 1 }}>
      <QueryClientProvider client={queryClient}>
        <SafeAreaProvider>
          {showMain && (
            <PlayerProgressProvider>
            <AudioProvider>
            <StatusBar barStyle="light-content" backgroundColor="transparent" translucent />
            <View style={{ flex: 1 }}>
              <AuroraBackground />
              <View style={{ flex: 1, backgroundColor: colors.background }}>
                <View style={{ flex: 1, paddingBottom: insets.bottom, backgroundColor: colors.background }}>
                  <Stack
                    screenOptions={{
                      headerShown: false,
                      contentStyle: { backgroundColor: 'transparent' },
                    }}
                  >
                    <Stack.Screen name="index" />
                    <Stack.Screen name="player" options={{ animation: 'none' }} />
                  </Stack>
                </View>
                <MiniPlayerWrapper />
                <LyricsModal />
                <PlayerOverlay />
                <SearchSelectionOverlay />
                <SingerSelectionOverlay />
              </View>
            </View>
            <Toast />
            </AudioProvider>
            </PlayerProgressProvider>
          )}
          {!splashDone && (
            <SplashScreen onPrepare={() => setShowMain(true)} onFinish={() => setSplashDone(true)} />
          )}
          {showMain && <ContactModal />}
        </SafeAreaProvider>
      </QueryClientProvider>
    </GestureHandlerRootView>
  );
}

import React, { useState, useEffect } from 'react';
import { StatusBar, View } from 'react-native';
import { Stack } from 'expo-router';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import { QueryClientProvider } from '@tanstack/react-query';
import { SafeAreaProvider } from 'react-native-safe-area-context';
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
import SplashScreen from '../components/SplashScreen';
import { ContactModal } from '../components/ContactModal';

TrackPlayer.registerPlaybackService(() => PlaybackService);

export default function RootLayout() {
  const [showMain, setShowMain] = useState(false);
  const [splashDone, setSplashDone] = useState(false);

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

  return (
    <GestureHandlerRootView style={{ flex: 1 }}>
      <QueryClientProvider client={queryClient}>
        <SafeAreaProvider>
          {showMain && (
            <AudioProvider>
            <StatusBar barStyle="light-content" backgroundColor="transparent" translucent />
            <View style={{ flex: 1, backgroundColor: colors.background }}>
              <AuroraBackground />
              <Stack
                screenOptions={{
                  headerShown: false,
                  contentStyle: { backgroundColor: 'transparent' },
                }}
              >
                <Stack.Screen name="index" />
                <Stack.Screen name="player" options={{ animation: 'none' }} />
              </Stack>
              <MiniPlayerWrapper />
              <LyricsModal />
              <PlayerOverlay />
            </View>
            <Toast />
            </AudioProvider>
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

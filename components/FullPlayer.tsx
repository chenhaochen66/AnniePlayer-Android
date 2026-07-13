import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  View,
  Text,
  Image,
  TouchableOpacity,
  useWindowDimensions,
  StyleSheet,
  NativeSyntheticEvent,
  NativeScrollEvent,
  ScrollView,
  BackHandler,
} from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { useRouter } from 'expo-router';
import Animated, {
  useAnimatedStyle,
  useSharedValue,
  useAnimatedRef,
  useAnimatedReaction,
  runOnJS,
  scrollTo,
  withTiming,
  withSpring,
  withSequence,
  Easing,
} from 'react-native-reanimated';
import { LinearGradient } from 'expo-linear-gradient';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { usePlayerStore } from '../store/usePlayerStore';
import { useUIStore } from '../store/useUIStore';
import { usePlayerProgress } from '../contexts/PlayerProgressContext';
import { useColorStore } from '../store/useColorStore';
import { useDownloadStore } from '../store/useDownloadStore';
import { useSharedAudio } from '../contexts/AudioContext';
import { MovingText } from './MovingText';
import { screenPadding } from '../constants/tokens';
import { LoopMode, Quality } from '../types';
import { parseLrcWithTranslation, LyricLine, findCurrentLineIndex } from '../utils/lrcParser';
import { fetchDownloadUrlBySong, fetchLyric } from '../services/cnmusicApi';
import { getFileUrl as getQobuzFileUrl } from '../services/qobuzApi';
import { useQobuzStore } from '../store/useQobuzStore';
import { enqueueDownload } from '../utils/downloadQueue';
import { QualityPicker } from './QualityPicker';
import { QueueModal } from './QueueModal';
import { QueueSection } from './QueueSection';
import { downloadSong } from '../services/downloadService';

const LyricItem = React.memo(
  ({ item, index, primaryColor, onMeasure, animate, currentLineIndex, targetOpacity, targetShadow, showTranslation, fontScale }: { item: LyricLine; index: number; primaryColor: string; onMeasure?: (index: number, height: number) => void; animate?: boolean; currentLineIndex: number; targetOpacity: number; targetShadow: number; showTranslation: boolean; fontScale: number }) => {
    const isActive = index === currentLineIndex;
    const d = index - currentLineIndex;
    const opacity = useSharedValue(targetOpacity);
    const fadeAnim = useSharedValue(showTranslation ? 1 : 0);
    const scale = useSharedValue(1);

    useEffect(() => {
      if (animate === false || Math.abs(d) > 1) {
        opacity.value = targetOpacity;
        return;
      }
      opacity.value = withTiming(targetOpacity, { duration: 400, easing: Easing.inOut(Easing.ease) });
    }, [targetOpacity, animate, index, currentLineIndex]);

    useEffect(() => {
      fadeAnim.value = withTiming(showTranslation ? 1 : 0, { duration: 200 });
    }, [showTranslation]);

    const prevActive = useRef(isActive);
    useEffect(() => {
      if (isActive && animate !== false) {
        scale.value = withSequence(
          withTiming(0.92, { duration: 0 }),
          withTiming(1.05, { duration: 100 }),
          withSpring(1.0, { damping: 10, stiffness: 150 }),
        );
      } else if (!isActive && scale.value !== 1) {
        scale.value = 1;
      }
      prevActive.current = isActive;
    }, [isActive, animate]);

    const rStyle = useAnimatedStyle(() => {
      if (scale.value !== 1) {
        return { opacity: opacity.value, transform: [{ scale: scale.value }] };
      }
      return { opacity: opacity.value };
    });

    const transRStyle = useAnimatedStyle(() => {
      const op = opacity.value * fadeAnim.value;
      if (scale.value !== 1) {
        return { opacity: op, transform: [{ scale: scale.value }] };
      }
      return { opacity: op };
    });

    const lineStyle = {
      fontSize: 34 * fontScale,
      lineHeight: 40 * fontScale,
      fontWeight: (isActive ? '800' : '500') as '400' | '700' | '800' | '500',
      color: '#fff',
      textAlign: 'left' as const,
      textShadowColor: isActive ? primaryColor : 'transparent',
      textShadowRadius: targetShadow,
    };

    const transStyle = {
      fontSize: 17 * fontScale,
      lineHeight: 28 * fontScale,
      fontWeight: (isActive ? '800' : '500') as '400' | '700' | '800' | '500',
      color: '#fff',
      textAlign: 'left' as const,
      textShadowColor: isActive ? primaryColor : 'transparent',
      textShadowRadius: targetShadow,
    };

    return (
      <View
        onLayout={(e) => onMeasure?.(index, e.nativeEvent.layout.height)}
        style={{
          paddingVertical: 10,
          justifyContent: 'center',
          paddingLeft: 40,
          paddingRight: 40,
          backgroundColor: isActive ? `${primaryColor}12` : 'transparent',
          borderRadius: isActive ? 12 : 0,
        }}
      >
        {isActive && (
          <View style={{
            position: 'absolute',
            left: 24,
            top: 12,
            bottom: 12,
            width: 3,
            borderRadius: 1.5,
            backgroundColor: primaryColor,
          }} />
        )}
        {animate === false ? (
          <Text style={[lineStyle, { opacity: targetOpacity }]}>{item.text}</Text>
        ) : (
          <Animated.Text style={[lineStyle, rStyle]}>{item.text}</Animated.Text>
        )}
        {item.translation ? (
          animate === false ? (
            <Text style={[transStyle, { opacity: targetOpacity * (showTranslation ? 1 : 0) }]}>{item.translation}</Text>
          ) : (
            <Animated.Text style={[transStyle, transRStyle]}>{item.translation}</Animated.Text>
          )
        ) : null}
      </View>
    );
  },
  (prev, next) =>
    prev.index === next.index &&
    prev.primaryColor === next.primaryColor &&
    prev.animate === next.animate &&
    prev.targetOpacity === next.targetOpacity &&
    prev.targetShadow === next.targetShadow &&
    prev.showTranslation === next.showTranslation &&
    prev.fontScale === next.fontScale,
);


function coverSize(w: number, h: number) { return Math.min(w * 0.78, h * 0.5); }
function targetLineY(h: number) { return h * 0.02; }

const PlayerGradient = React.memo(({ children }: { children: React.ReactNode }) => {
  const palette = useColorStore((s) => s.colors);
  const gradientColors: [string, string, ...string[]] = [
    palette.primary,
    palette.primary + '99',
    palette.background,
  ];
  return (
    <LinearGradient colors={gradientColors} start={{ x: 0.5, y: 0 }} end={{ x: 0.5, y: 0.8 }} style={{ flex: 1 }}>
      {children}
    </LinearGradient>
  );
});

const PlayerProgressBar = React.memo(() => {
  const { width: winW } = useWindowDimensions();
  const { position, duration } = usePlayerProgress();
  const setCurrentTime = usePlayerStore((s) => s.setCurrentTime);
  const setDragging = useUIStore((s) => s.setDragging);
  const seekRatio = useUIStore((s) => s.seekRatio);
  const { seek } = useSharedAudio();

  const seekViewRef = useRef<View>(null);
  const seekWidthRef = useRef(winW - screenPadding.horizontal * 2);
  const seekLeftRef = useRef(screenPadding.horizontal);
  const dragRatioRef = useRef(0);
  const lastSeekFrameRef = useRef(0);

  useEffect(() => {
    seekViewRef.current?.measureInWindow((x) => {
      if (x != null) seekLeftRef.current = x;
    });
  }, []);

  const isDragging = useUIStore((s) => s.isDragging);

  const handleSeekMove = useCallback(
    (e: any) => {
      const pageX = e.nativeEvent?.pageX ?? 0;
      const localX = pageX - seekLeftRef.current;
      const ratio = Math.max(0, Math.min(1, localX / seekWidthRef.current));
      dragRatioRef.current = ratio;
      const now = Date.now();
      if (now - lastSeekFrameRef.current > 16) {
        lastSeekFrameRef.current = now;
        setDragging(true, ratio);
      }
    },
    [setDragging],
  );

  const handleSeekRelease = useCallback(() => {
    const ratio = dragRatioRef.current;
    const d = duration.value;
    const t = ratio * d;
    position.value = t;
    seek(t);
    setDragging(false);
  }, [seek, setDragging]);

  const fillStyle = useAnimatedStyle(() => {
    const progress = isDragging ? seekRatio : (duration.value > 0 ? position.value / duration.value : 0);
    return { width: `${progress * 100}%` };
  }, [isDragging, seekRatio]);

  const thumbStyle = useAnimatedStyle(() => {
    const progress = isDragging ? seekRatio : (duration.value > 0 ? position.value / duration.value : 0);
    return { left: `${progress * 100}%` };
  }, [isDragging, seekRatio]);

  const formatTime = (s: number) => {
    const m = Math.floor(Math.max(0, s) / 60);
    const sec = Math.floor(Math.max(0, s) % 60);
    return `${m}:${sec.toString().padStart(2, '0')}`;
  };

  const [displayTime, setDisplayTime] = useState(0);
  const [displayDuration, setDisplayDuration] = useState(0);
  useAnimatedReaction(
    () => position.value,
    (pos) => { runOnJS(setDisplayTime)(pos); },
    [],
  );
  useAnimatedReaction(
    () => duration.value,
    (dur) => { runOnJS(setDisplayDuration)(dur); },
    [],
  );

  return (
    <View style={[styles.progressWrap, { paddingHorizontal: screenPadding.horizontal }]}>
      <View
        onStartShouldSetResponderCapture={() => true}
        onMoveShouldSetResponderCapture={() => true}
        ref={seekViewRef}
        onResponderGrant={(e) => { handleSeekMove(e); }}
        onResponderMove={(e) => { handleSeekMove(e); }}
        onResponderRelease={() => { handleSeekRelease(); }}
        style={styles.seekTouch}
        onLayout={(e) => { seekWidthRef.current = e.nativeEvent.layout.width; seekViewRef.current?.measureInWindow((x) => { if (x != null) seekLeftRef.current = x; }); }}
      >
        <View style={styles.track}>
          <Animated.View style={[styles.fill, fillStyle]} />
          <Animated.View style={[styles.thumb, thumbStyle]} />
        </View>
      </View>
      <View style={styles.timeRow}>
        <Text style={styles.timeText}>{formatTime(isDragging ? seekRatio * duration.value : displayTime)}</Text>
        <Text style={[styles.timeText, { color: 'rgba(255,255,255,0.2)' }]}>
          {formatTime(displayDuration)}
        </Text>
      </View>
    </View>
  );
});

const PlayerControls = React.memo(() => {
  const isPlaying = usePlayerStore((s) => s.isPlaying);
  const loopMode = usePlayerStore((s) => s.loopMode);
  const next = usePlayerStore((s) => s.next);
  const previous = usePlayerStore((s) => s.previous);
  const setLoopMode = usePlayerStore((s) => s.setLoopMode);
  const togglePlay = useSharedAudio().togglePlay!;
  const queueVisible = useUIStore((s) => s.queueVisible);
  const setQueueVisible = useUIStore((s) => s.setQueueVisible);

  const loopData: Record<LoopMode, { icon: keyof typeof Ionicons.glyphMap; label: string }> = {
    sequential: { icon: 'repeat', label: '列表循环' },
    single: { icon: 'repeat', label: '单曲循环' },
    shuffle: { icon: 'shuffle', label: '随机播放' },
  };

  return (
    <View style={[styles.controlsRow, { paddingHorizontal: screenPadding.horizontal }]}>
      <TouchableOpacity onPress={() => setLoopMode(
        loopMode === 'sequential' ? 'single' : loopMode === 'single' ? 'shuffle' : 'sequential',
      )} activeOpacity={0.7}>
        <View style={{ position: 'relative' }}>
          <Ionicons name={loopData[loopMode].icon} size={24} color="rgba(255,255,255,0.7)" />
          {loopMode === 'single' && (
            <Text style={{ position: 'absolute', top: 1, right: 1, fontSize: 9, fontWeight: '800', color: 'rgba(255,255,255,0.7)' }}>1</Text>
          )}
        </View>
      </TouchableOpacity>
      <TouchableOpacity onPress={previous} activeOpacity={0.7}>
        <Ionicons name="play-skip-back" size={24} color="rgba(255,255,255,0.7)" />
      </TouchableOpacity>
      <TouchableOpacity onPress={togglePlay} activeOpacity={0.8}>
        <View style={styles.playBtn}>
          <Ionicons name={isPlaying ? 'pause' : 'play'} size={26} color="#1a1a2e" style={{ marginLeft: isPlaying ? 0 : 3 }} />
        </View>
      </TouchableOpacity>
      <TouchableOpacity onPress={() => next(true)} activeOpacity={0.7}>
        <Ionicons name="play-skip-forward" size={24} color="rgba(255,255,255,0.7)" />
      </TouchableOpacity>
      <QueueSection />
    </View>
  );
});

const PlayerVolumeSlider = React.memo(() => {
  const { width: winW } = useWindowDimensions();
  const volume = usePlayerStore((s) => s.volume);
  const setVolume = usePlayerStore((s) => s.setVolume);
  const { setVolume: setAudioVolume } = useSharedAudio();
  const { bottom } = useSafeAreaInsets();
  const volWidthRef = useRef(winW - 68);

  const handleVolume = useCallback(
    (e: any) => {
      const x = e.nativeEvent?.locationX ?? e.nativeEvent?.x ?? 0;
      const w = volWidthRef.current;
      const vol = Math.max(0, Math.min(1, x / w));
      setVolume(vol);
      setAudioVolume(vol);
    },
    [setVolume, setAudioVolume],
  );

  return (
    <View style={[styles.volumeRow, { paddingHorizontal: screenPadding.horizontal, marginBottom: 8 }]}>
      <Ionicons name="volume-low" size={14} color="rgba(255,255,255,0.25)" />
      <View
        onStartShouldSetResponder={() => true}
        onMoveShouldSetResponder={() => true}
        onResponderGrant={handleVolume}
        onResponderMove={handleVolume}
        style={styles.volumeTouch}
        onLayout={(e) => { volWidthRef.current = e.nativeEvent.layout.width; }}
      >
        <View style={styles.volumeTrack}>
          <View style={[styles.volumeFill, { width: `${volume * 100}%` }]} />
        </View>
      </View>
      <Ionicons name="volume-high" size={14} color="rgba(255,255,255,0.25)" />
    </View>
  );
});

const PlayerLyricsList = React.memo(({ fontScale, setFontScale, onOpenFontScale }: { fontScale: number; setFontScale: (v: number) => void; onOpenFontScale: () => void }) => {
  const { height: winH } = useWindowDimensions();
  const currentSong = usePlayerStore((s) => s.currentSong);
  const { position, duration } = usePlayerProgress();
  const palette = useColorStore((s) => s.colors);
  const isDragging = useUIStore((s) => s.isDragging);
  const [flatListReady, setFlatListReady] = useState(false);
  const flatListRef = useAnimatedRef<Animated.FlatList<any>>();
  const scrollOffset = useSharedValue(0);
  const scrollAnimating = useSharedValue(false);
  const [userScrolling, setUserScrolling] = useState(false);
  const measuredHeightsRef = useRef<number[]>([]);
  const showTranslation = useUIStore((s) => s.showLyricsTranslation);
  const [heightsVersion, setHeightsVersion] = useState(0);

  useAnimatedReaction(
    () => scrollOffset.value,
    (value) => {
      scrollTo(flatListRef, 0, value, false);
    },
  );

  const lyricsData = useMemo(() => {
    if (!currentSong?.lyric) return [];
    return parseLrcWithTranslation(currentSong.lyric, currentSong.tlyric);
  }, [currentSong?.lyric, currentSong?.tlyric]);

  const setStoreCurrentLineIndex = useUIStore((s) => s.setCurrentLineIndex);
  const [currentLineIndex, setCurrentLineIndex] = useState(-1);
  const currentLineIndexRef = useRef(-1);
  const prevLineIndexRef = useRef(-1);

  const updateLyricIndex = useCallback((time: number) => {
    const u = useUIStore.getState();
    const t = u.isDragging ? u.seekRatio * duration.value : time;
    const newIndex = findCurrentLineIndex(lyricsData, t);
    if (newIndex !== currentLineIndexRef.current) {
      currentLineIndexRef.current = newIndex;
      setCurrentLineIndex(newIndex);
      setStoreCurrentLineIndex(newIndex);
    }
  }, [lyricsData, duration]);

  useAnimatedReaction(
    () => position.value,
    (pos) => { runOnJS(updateLyricIndex)(pos); },
    [updateLyricIndex],
  );

  // initial update when lyrics load
  useEffect(() => {
    if (lyricsData.length) updateLyricIndex(position.value);
  }, [lyricsData]);

  const handleMeasure = useCallback((index: number, height: number) => {
    if (measuredHeightsRef.current[index] !== height) {
      measuredHeightsRef.current[index] = height;
      setHeightsVersion(v => v + 1);
    }
  }, []);

  useEffect(() => {
    setFlatListReady(false);
    measuredHeightsRef.current = [];
    setUserScrolling(false);
    currentLineIndexRef.current = -1;
    setCurrentLineIndex(-1);
    setStoreCurrentLineIndex(-1);
    scrollAnimating.value = false;
    prevLineIndexRef.current = -1;
  }, [currentSong?.id]);

  useEffect(() => {
    if (!flatListReady || userScrolling) return;
    if (currentLineIndex < 0 && lyricsData.length === 0) return;
    const idx = currentLineIndex < 0 ? 0 : currentLineIndex;
    let offset = winH * 0.18;
    for (let i = 0; i < idx; i++) {
      offset += measuredHeightsRef.current[i] ?? 50;
    }
    const target = Math.max(0, offset - targetLineY(winH));
    const prev = prevLineIndexRef.current;
    prevLineIndexRef.current = idx;
    if (idx === 0) {
      scrollOffset.value = target;
    } else {
      const lineDiff = Math.abs(idx - (prev >= 0 ? prev : 0));
      const duration = lineDiff <= 3 ? 550 : 0;
      scrollOffset.value = withTiming(target, { duration, easing: Easing.bezier(0.25, 0.1, 0.25, 1) });
    }
  }, [currentLineIndex, flatListReady, userScrolling, isDragging, lyricsData.length, fontScale, heightsVersion]);

  const renderLyricItem = useCallback(
    ({ item, index }: { item: LyricLine; index: number }) => {
      const isActive = index === currentLineIndex;
      const isPast = index < currentLineIndex;
      const d = index - currentLineIndex;
      const targetOpacity = isPast ? 0.12 : isActive ? 1 : Math.max(0.12, 0.45 - (d - 1) * 0.2);
      const targetShadow = isActive ? 20 : 0;
      return (
        <LyricItem item={item} index={index} primaryColor={palette.primary} onMeasure={handleMeasure} animate={!isDragging} currentLineIndex={currentLineIndex} targetOpacity={targetOpacity} targetShadow={targetShadow} showTranslation={showTranslation} fontScale={fontScale} />
      );
    },
    [palette.primary, handleMeasure, isDragging, currentLineIndex, showTranslation, fontScale],
  );

  const handleScrollBegin = useCallback(() => {
    setUserScrolling(true);
  }, []);

  const handleScrollEnd = useCallback(() => {
    setUserScrolling(false);
  }, []);

  if (!currentSong) return null;

  if (lyricsData.length > 0) {
    return (
      <View style={{ flex: 1 }}>
        <Animated.FlatList
          key={currentSong?.id}
          ref={flatListRef}
          data={lyricsData}
          renderItem={renderLyricItem}
          keyExtractor={(_, index) => String(index)}
          onScrollBeginDrag={handleScrollBegin}
          onMomentumScrollBegin={handleScrollBegin}
          onMomentumScrollEnd={handleScrollEnd}
          onLayout={() => setFlatListReady(true)}
          nestedScrollEnabled
          contentContainerStyle={{
            flexGrow: 1,
            paddingVertical: winH * 0.18,
          }}
          showsVerticalScrollIndicator={false}
        />

        <TouchableOpacity
          onPress={onOpenFontScale}
          activeOpacity={0.6}
          style={{
            position: 'absolute', top: 8, right: 16,
            width: 32, height: 32, borderRadius: 16,
            backgroundColor: 'rgba(255,255,255,0.06)',
            alignItems: 'center', justifyContent: 'center',
          }}
        >
          <Text style={{ color: '#fff', fontSize: 13, fontWeight: '700' }}>Aa</Text>
        </TouchableOpacity>
      </View>
    );
  }

  return (
    <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center' }}>
      <Text style={{ color: 'rgba(255,255,255,0.5)', fontSize: 16 }}>暂无歌词</Text>
    </View>
  );
});

interface FullPlayerProps {
  onClose?: () => void;
}

export const FullPlayer = React.memo(({ onClose }: FullPlayerProps) => {
  const router = useRouter();
  const { width: winW, height: winH } = useWindowDimensions();
  const { top, bottom } = useSafeAreaInsets();
  const pagerMaxH = winH * 0.55;
  const cvSize = coverSize(winW, winH);
  const [coverError, setCoverError] = useState(false);
  const [pageIndex, setPageIndex] = useState(0);
  const [showQualityPicker, setShowQualityPicker] = useState(false);
  const [downloadingQuality, setDownloadingQuality] = useState<Quality | null>(null);
  const scrollRef = useRef<ScrollView>(null);
  const isSwipingRef = useRef(false);

  const [fontScale, setFontScale] = useState(0.75);
  const [fontScaleVisible, setFontScaleVisible] = useState(false);
  const fontScaleLevels = [0.5, 0.625, 0.75, 0.875, 1];
  const fontPanelH = 360;
  const fontTranslateY = useSharedValue(fontPanelH);
  const fontOverlayOpacity = useSharedValue(0);

  useEffect(() => {
    const config = { duration: 150, easing: Easing.out(Easing.cubic) };
    fontTranslateY.value = fontScaleVisible ? withTiming(0, config) : withTiming(fontPanelH, config);
    fontOverlayOpacity.value = fontScaleVisible ? withTiming(1, config) : withTiming(0, config);
  }, [fontScaleVisible, fontPanelH]);

  useEffect(() => {
    if (!fontScaleVisible) return;
    const sub = BackHandler.addEventListener('hardwareBackPress', () => {
      setFontScaleVisible(false);
      return true;
    });
    return () => sub.remove();
  }, [fontScaleVisible]);

  const fontPanelStyle = useAnimatedStyle(() => ({
    transform: [{ translateY: fontTranslateY.value }],
  }));

  const fontOverlayStyle = useAnimatedStyle(() => ({
    opacity: fontOverlayOpacity.value,
  }));

  const currentSong = usePlayerStore((s) => s.currentSong);
  const isPlaying = usePlayerStore((s) => s.isPlaying);
  const setDuration = usePlayerStore((s) => s.setDuration);
  const addDownloadedId = usePlayerStore((s) => s.addDownloadedId);
  const addToQueue = usePlayerStore((s) => s.addToQueue);
  const addTask = useDownloadStore((s) => s.addTask);
  const updateProgress = useDownloadStore((s) => s.updateProgress);
  const completeTask = useDownloadStore((s) => s.completeTask);
  const failTask = useDownloadStore((s) => s.failTask);
  const showTranslation = useUIStore((s) => s.showLyricsTranslation);
  const setShowTranslation = useUIStore((s) => s.setShowLyricsTranslation);

  const animatedContentStyle = useAnimatedStyle(() => ({
    transform: [{ translateY: 0 }],
  }));

  const handleBack = useCallback(() => {
    if (onClose) onClose();
    else router.back();
  }, [onClose, router]);

  useEffect(() => {
    setDuration(currentSong?.duration || 0);
  }, [currentSong]);

  useEffect(() => {
    setCoverError(false);
  }, [currentSong?.id]);

  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTo({ x: pageIndex * winW, animated: true });
    }
  }, [pageIndex]);

  const handleDownload = useCallback(() => {
    if (!currentSong) return;
    if ((currentSong as any).platform === 'qobuz') {
      const token = useQobuzStore.getState().getActiveToken();
      if (!token) return;
      setDownloadingQuality('flac');
      addTask(currentSong, 'flac');
      enqueueDownload(currentSong.id, async () => {
        try {
          const rawId = currentSong.id.replace(/^qobuz:/, '');
          const info = await getQobuzFileUrl(rawId, 27, token);
          const filePath = await downloadSong(
            currentSong.id, currentSong.title, currentSong.artist,
            currentSong.cover, currentSong.album, info.url, 'flac',
            (p) => updateProgress(currentSong.id, p * 100),
            currentSong.lyric || '', currentSong.tlyric || '',
            currentSong.albumArtist, currentSong.trackNumber, currentSong.discNumber, currentSong.releaseDate,
          );
          completeTask(currentSong.id, filePath);
          addDownloadedId(currentSong.id);
          addToQueue(currentSong);
        } catch {
          failTask(currentSong.id);
        }
        setDownloadingQuality(null);
      });
    } else {
      setShowQualityPicker(true);
    }
  }, [currentSong, addTask, updateProgress, completeTask, addDownloadedId, addToQueue, failTask]);

  const handleDownloadWithQuality = useCallback((quality: Quality) => {
    if (!currentSong) return;
    setShowQualityPicker(false);
    enqueueDownload(currentSong.id, async () => {
      setDownloadingQuality(quality);
      addTask(currentSong, quality);
      try {
        const url = await fetchDownloadUrlBySong(currentSong, quality);
        const { lrc: lyric, tlyric } = currentSong.lyric && currentSong.tlyric
          ? { lrc: currentSong.lyric, tlyric: currentSong.tlyric }
          : await fetchLyric(currentSong.id, currentSong.title, currentSong.artist);
        const filePath = await downloadSong(
          currentSong.id, currentSong.title, currentSong.artist,
          currentSong.cover, currentSong.album, url, quality,
          (p) => updateProgress(currentSong.id, p * 100),
          lyric, tlyric ?? undefined,
          currentSong.albumArtist, currentSong.trackNumber, currentSong.discNumber, currentSong.releaseDate,
        );
        completeTask(currentSong.id, filePath);
        addDownloadedId(currentSong.id);
        addToQueue(currentSong);
      } catch {
        failTask(currentSong.id);
      }
      setDownloadingQuality(null);
    });
  }, [currentSong, addTask, updateProgress, completeTask, addDownloadedId, addToQueue, failTask]);

  const handleMomentumEnd = useCallback((e: NativeSyntheticEvent<NativeScrollEvent>) => {
    const idx = Math.round(e.nativeEvent.contentOffset.x / winW);
    setPageIndex(idx);
  }, [winW]);

  if (!currentSong) {
    return (
      <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center', backgroundColor: '#0a0a1a' }}>
        <Text style={{ color: 'rgba(255,255,255,0.4)' }}>暂无播放歌曲</Text>
      </View>
    );
  }

  return (
    <Animated.View style={[{ flex: 1 }, animatedContentStyle]}>
    <PlayerGradient>
      <View style={{ flex: 1, paddingTop: top, paddingBottom: Math.max(12, bottom) }}>
          <View style={{ flex: 1, maxHeight: pagerMaxH }}>
          <View style={[styles.headerRow, { paddingHorizontal: screenPadding.horizontal }]}>
            <View style={styles.dragBar} />
          </View>

          <View style={{ flexDirection: 'row', alignItems: 'center', paddingHorizontal: 12, paddingVertical: 6 }}>
            <TouchableOpacity onPress={handleBack} activeOpacity={0.7} style={styles.backBtn}>
              <Ionicons name="chevron-down" size={22} color="rgba(255,255,255,0.7)" />
            </TouchableOpacity>

            <View style={{ flex: 1, alignItems: 'center' }}>
              <TouchableOpacity
                onPress={() => {
                  const nextPage = pageIndex === 0 ? 1 : 0;
                  setPageIndex(nextPage);
                }}
                activeOpacity={0.7}
                style={{
                  flexDirection: 'row',
                  backgroundColor: 'rgba(255,255,255,0.08)',
                  borderRadius: 14,
                  paddingHorizontal: 4,
                  paddingVertical: 3,
                }}
              >
                <View style={{
                  paddingHorizontal: 12, paddingVertical: 4, borderRadius: 11,
                  backgroundColor: pageIndex === 0 ? 'rgba(255,255,255,0.15)' : 'transparent',
                }}>
                  <Text style={{ color: pageIndex === 0 ? '#fff' : 'rgba(255,255,255,0.5)', fontSize: 13, fontWeight: '600' }}>
                    歌曲
                  </Text>
                </View>
                <View style={{
                  paddingHorizontal: 12, paddingVertical: 4, borderRadius: 11,
                  backgroundColor: pageIndex === 1 ? 'rgba(255,255,255,0.15)' : 'transparent',
                }}>
                  <Text style={{ color: pageIndex === 1 ? '#fff' : 'rgba(255,255,255,0.5)', fontSize: 13, fontWeight: '600' }}>
                    歌词
                  </Text>
                </View>
              </TouchableOpacity>
            </View>

            <View style={{ width: 34 }} />
          </View>

          <View style={{ flex: 1, minHeight: 120 }}>
            <ScrollView
              ref={scrollRef}
              horizontal
              pagingEnabled
              showsHorizontalScrollIndicator={false}
              onScrollBeginDrag={() => { isSwipingRef.current = true; }}
              onScrollEndDrag={() => { setTimeout(() => { isSwipingRef.current = false; }, 100); }}
              onScroll={({ nativeEvent }) => {
                if (!isSwipingRef.current) return;
                const idx = Math.round(nativeEvent.contentOffset.x / winW);
                if (idx !== pageIndex) setPageIndex(idx);
              }}
              scrollEventThrottle={16}
              onMomentumScrollEnd={handleMomentumEnd}
              bounces={false}
            >
              <View style={{ width: winW, justifyContent: 'center', alignItems: 'center' }}>
                <View style={{ width: cvSize, height: cvSize, borderRadius: 16, shadowColor: '#000', shadowOffset: { width: 0, height: 10 }, shadowOpacity: 0.45, shadowRadius: 24, elevation: 12 }}>
                  <View>
                    {coverError ? (
                      <View style={{ width: cvSize, height: cvSize, borderRadius: 16, alignItems: 'center', justifyContent: 'center', backgroundColor: 'rgba(255,255,255,0.04)' }}>
                        <Ionicons name="musical-note" size={44} color="rgba(255,255,255,0.15)" />
                      </View>
                    ) : (
                      <Image source={{ uri: currentSong.cover }} style={{ width: cvSize, height: cvSize, borderRadius: 16 }} onError={() => setCoverError(true)} />
                    )}
                  </View>
                </View>
              </View>

              <View style={{ width: winW }}>
                <PlayerLyricsList fontScale={fontScale} setFontScale={setFontScale} onOpenFontScale={() => setFontScaleVisible(true)} />
              </View>
            </ScrollView>
          </View>
        </View>

        <View style={{ height: 40 }} />
        <View style={[styles.infoRow, { paddingHorizontal: screenPadding.horizontal }]}>
          <View style={{ flex: 1, overflow: 'hidden' }}>
            <MovingText text={currentSong.title} threshold={20} style={styles.title} />
          </View>
          {currentSong?.tlyric ? (
            <TouchableOpacity
              onPress={() => setShowTranslation(!showTranslation)}
              activeOpacity={0.6}
              style={{ paddingHorizontal: 8, paddingVertical: 4, borderRadius: 10, backgroundColor: showTranslation ? 'rgba(255,255,255,0.15)' : 'transparent', marginRight: 4 }}
            >
              <Text style={{ fontSize: 11, color: showTranslation ? '#fff' : 'rgba(255,255,255,0.4)', fontWeight: '600' }}>译</Text>
            </TouchableOpacity>
          ) : null}
          <TouchableOpacity onPress={handleDownload} activeOpacity={0.7} style={{ padding: 4, marginLeft: 4 }}>
            <Ionicons name="download-outline" size={18} color="rgba(255,255,255,0.35)" />
          </TouchableOpacity>
        </View>
        {currentSong.artist ? (
          <Text style={[styles.artist, { paddingHorizontal: screenPadding.horizontal }]} numberOfLines={1}>{currentSong.artist}</Text>
        ) : null}

        <PlayerProgressBar />

        <PlayerControls />

        <PlayerVolumeSlider />

      </View>

        <QualityPicker
          visible={showQualityPicker}
          qualities={currentSong?.qualities || ['128k']}
          onSelect={handleDownloadWithQuality}
          onCancel={() => setShowQualityPicker(false)}
        />

        {downloadingQuality && (
          <View style={{ position: 'absolute', bottom: bottom + 80, left: 20, right: 20, alignItems: 'center' }}>
            <View style={{ backgroundColor: 'rgba(26,26,46,0.95)', borderRadius: 12, paddingVertical: 10, paddingHorizontal: 20 }}>
              <Text style={{ color: '#818cf8', fontSize: 14, fontWeight: '600' }}>
                正在下载 {currentSong?.title}
              </Text>
            </View>
          </View>
        )}

    </PlayerGradient>
      <QueueModal />
      {fontScaleVisible && (
        <View style={{ position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, zIndex: 200 }} pointerEvents="auto">
          <Animated.View style={[{ flex: 1, backgroundColor: 'rgba(0,0,0,0.6)' }, fontOverlayStyle]}>
            <TouchableOpacity activeOpacity={1} onPress={() => setFontScaleVisible(false)} style={{ flex: 1 }} />
          </Animated.View>
          <Animated.View
            style={[{
              position: 'absolute', bottom: 0, left: 0, right: 0,
              backgroundColor: '#1a1a2e',
              borderTopLeftRadius: 20,
              borderTopRightRadius: 20,
              paddingTop: 20,
              paddingBottom: 16,
              maxHeight: fontPanelH,
            }, fontPanelStyle]}
          >
            <View style={{ width: 36, height: 4, borderRadius: 2, backgroundColor: 'rgba(255,255,255,0.2)', alignSelf: 'center', marginBottom: 16 }} />
            <View style={{ flexDirection: 'row', alignItems: 'center', paddingHorizontal: 20, marginBottom: 12 }}>
              <Text style={{ color: '#fff', fontSize: 17, fontWeight: '700', flex: 1 }}>歌词字号</Text>
              <TouchableOpacity onPress={() => setFontScaleVisible(false)} activeOpacity={0.7} style={{ padding: 4 }}>
                <Ionicons name="close" size={20} color="rgba(255,255,255,0.5)" />
              </TouchableOpacity>
            </View>
            <ScrollView bounces={false}>
            {fontScaleLevels.map((scale, i) => (
              <TouchableOpacity
                key={i}
                activeOpacity={0.7}
                onPress={() => { setFontScale(scale); setFontScaleVisible(false); }}
                style={{
                  flexDirection: 'row',
                  alignItems: 'center',
                  paddingVertical: 14,
                  paddingHorizontal: 20,
                  backgroundColor: fontScale === scale ? 'rgba(99,102,241,0.08)' : 'transparent',
                }}
              >
                <Text style={{
                  fontSize: 11 + scale * 16,
                  color: fontScale === scale ? '#818cf8' : '#fff',
                  fontWeight: fontScale === scale ? '700' : '500',
                  flex: 1,
                }}>
                  Aa
                </Text>
                <View style={{
                  width: 10, height: 10, borderRadius: 5,
                  backgroundColor: fontScale === scale ? '#818cf8' : 'rgba(255,255,255,0.12)',
                }} />
              </TouchableOpacity>
            ))}
            </ScrollView>
          </Animated.View>
        </View>
      )}
    </Animated.View>
  );
});

const styles = StyleSheet.create({
  headerRow: {
    alignItems: 'center',
    paddingTop: 4,
    paddingBottom: 2,
  },
  dragBar: {
    width: 36,
    height: 4,
    borderRadius: 2,
    backgroundColor: 'rgba(255,255,255,0.25)',
  },
  backBtn: {
    width: 34,
    height: 34,
    borderRadius: 17,
    alignItems: 'center',
    justifyContent: 'center',
  },
  cover: {
    borderRadius: 16,
  },
  infoRow: {
    flexDirection: 'row',
    alignItems: 'center',
    height: 30,
    marginTop: 0,
  },
  title: {
    color: '#fff',
    fontSize: 20,
    fontWeight: '700',
  },
  artist: {
    color: 'rgba(255,255,255,0.5)',
    fontSize: 14,
    marginTop: 1,
    marginBottom: 2,
  },
  progressWrap: {
    marginTop: 2,
  },
  seekTouch: {
    height: 26,
    justifyContent: 'center',
  },
  track: {
    height: 4,
    backgroundColor: 'rgba(255,255,255,0.08)',
    borderRadius: 2,
    position: 'relative',
  },
  fill: {
    height: 4,
    backgroundColor: '#fff',
    borderRadius: 2,
    shadowColor: '#fff',
    shadowOffset: { width: 0, height: 0 },
    shadowOpacity: 0.3,
    shadowRadius: 6,
    elevation: 2,
  },
  thumb: {
    position: 'absolute',
    top: -5.5,
    width: 15,
    height: 15,
    borderRadius: 7.5,
    backgroundColor: '#fff',
    marginLeft: -7.5,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 1 },
    shadowOpacity: 0.2,
    shadowRadius: 4,
    elevation: 4,
  },
  timeRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginTop: 2,
  },
  timeText: {
    color: 'rgba(255,255,255,0.3)',
    fontSize: 11,
    fontVariant: ['tabular-nums'],
  },
  controlsRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 30,
    marginTop: 0,
  },
  playBtn: {
    width: 52,
    height: 52,
    borderRadius: 26,
    backgroundColor: '#fff',
    alignItems: 'center',
    justifyContent: 'center',
    shadowColor: '#fff',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.15,
    shadowRadius: 12,
    elevation: 6,
  },
  volumeRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    marginTop: 32,
    paddingHorizontal: 4,
  },
  volumeTouch: {
    flex: 1,
    height: 28,
    justifyContent: 'center',
  },
  volumeTrack: {
    height: 5,
    backgroundColor: 'rgba(255,255,255,0.08)',
    borderRadius: 2.5,
  },
  volumeFill: {
    height: 5,
    backgroundColor: 'rgba(255,255,255,0.3)',
    borderRadius: 2.5,
  },
});

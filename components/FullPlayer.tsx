import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  View,
  Text,
  Image,
  TouchableOpacity,
  Dimensions,
  StyleSheet,
  FlatList,
  NativeSyntheticEvent,
  NativeScrollEvent,
  ScrollView,
} from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { useRouter } from 'expo-router';
import Animated, {
  useAnimatedStyle,
} from 'react-native-reanimated';
import { LinearGradient } from 'expo-linear-gradient';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { usePlayerStore } from '../store/usePlayerStore';
import { useUIStore } from '../store/useUIStore';
import { useColorStore } from '../store/useColorStore';
import { useDownloadStore } from '../store/useDownloadStore';
import { useSharedAudio } from '../contexts/AudioContext';
import { MovingText } from './MovingText';
import { screenPadding } from '../constants/tokens';
import { LoopMode, Quality } from '../types';
import { parseLrc, LyricLine, findCurrentLineIndex } from '../utils/lrcParser';
import { fetchDownloadUrlBySong, fetchLyric } from '../services/cnmusicApi';
import { getFileUrl as getQobuzFileUrl } from '../services/qobuzApi';
import { useQobuzStore } from '../store/useQobuzStore';
import { enqueueDownload } from '../utils/downloadQueue';
import { QualityPicker } from './QualityPicker';
import { QueueModal } from './QueueModal';
import { QueueSection } from './QueueSection';
import { downloadSong } from '../services/downloadService';

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
            fontSize: isActive ? 19 : 14,
            fontWeight: isActive ? '700' : '400',
            color: isActive ? '#fff' : isPast ? 'rgba(255,255,255,0.25)' : 'rgba(255,255,255,0.45)',
            textAlign: 'center',
            textShadowColor: isActive ? primaryColor : 'transparent',
            textShadowRadius: isActive ? 18 : 0,
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

const { width: SCREEN_WIDTH, height: SCREEN_HEIGHT } = Dimensions.get('window');
const COVER_SIZE = Math.min(SCREEN_WIDTH * 0.78, SCREEN_HEIGHT * 0.44);
const PAGER_HEIGHT = SCREEN_HEIGHT * 0.5;
const TARGET_LINE_Y = SCREEN_HEIGHT * 0.205;

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
  const currentTime = usePlayerStore((s) => s.currentTime);
  const duration = usePlayerStore((s) => s.duration);
  const setCurrentTime = usePlayerStore((s) => s.setCurrentTime);
  const setDragging = useUIStore((s) => s.setDragging);
  const seekRatio = useUIStore((s) => s.seekRatio);
  const { seek } = useSharedAudio();

  const seekViewRef = useRef<View>(null);
  const seekWidthRef = useRef(SCREEN_WIDTH - screenPadding.horizontal * 2);
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
    const d = usePlayerStore.getState().duration;
    const t = ratio * d;
    setCurrentTime(t);
    seek(t);
    setDragging(false);
  }, [setCurrentTime, seek, setDragging]);

  const progress = isDragging ? seekRatio : (duration > 0 ? currentTime / duration : 0);

  const formatTime = (s: number) => {
    const m = Math.floor(Math.max(0, s) / 60);
    const sec = Math.floor(Math.max(0, s) % 60);
    return `${m}:${sec.toString().padStart(2, '0')}`;
  };

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
          <View style={[styles.fill, { width: `${progress * 100}%` }]} />
          <View style={[styles.thumb, { left: `${progress * 100}%` }]} />
        </View>
      </View>
      <View style={styles.timeRow}>
        <Text style={styles.timeText}>{formatTime(isDragging ? seekRatio * duration : currentTime)}</Text>
        <Text style={[styles.timeText, { color: 'rgba(255,255,255,0.2)' }]}>
          {formatTime(duration)}
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
      )} activeOpacity={0.7} style={{ alignItems: 'center', gap: 2 }}>
        <Ionicons name={loopData[loopMode].icon} size={20} color={loopMode !== 'sequential' ? '#fff' : 'rgba(255,255,255,0.3)'} />
        <Text style={{ color: loopMode !== 'sequential' ? 'rgba(255,255,255,0.5)' : 'transparent', fontSize: 8, lineHeight: 8 }}>
          {loopData[loopMode].label}
        </Text>
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
  const volume = usePlayerStore((s) => s.volume);
  const setVolume = usePlayerStore((s) => s.setVolume);
  const { setVolume: setAudioVolume } = useSharedAudio();
  const { bottom } = useSafeAreaInsets();
  const volWidthRef = useRef(SCREEN_WIDTH - 68);

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
    <View style={[styles.volumeRow, { paddingHorizontal: screenPadding.horizontal, marginBottom: bottom + 4 }]}>
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

const PlayerLyricsList = React.memo(() => {
  const currentSong = usePlayerStore((s) => s.currentSong);
  const currentTime = usePlayerStore((s) => s.currentTime);
  const duration = usePlayerStore((s) => s.duration);
  const palette = useColorStore((s) => s.colors);
  const isDragging = useUIStore((s) => s.isDragging);
  const seekRatio = useUIStore((s) => s.seekRatio);
  const [flatListReady, setFlatListReady] = useState(false);
  const flatListRef = useRef<FlatList>(null);
  const [userScrolling, setUserScrolling] = useState(false);
  const measuredHeightsRef = useRef<number[]>([]);

  const lyricsData = useMemo(() => {
    if (!currentSong?.lyric) return [];
    return parseLrc(currentSong.lyric);
  }, [currentSong?.lyric]);

  const currentLineIndex = useMemo(
    () => findCurrentLineIndex(lyricsData, isDragging ? seekRatio * duration : currentTime),
    [lyricsData, seekRatio, currentTime, duration, isDragging],
  );

  const handleMeasure = useCallback((index: number, height: number) => {
    measuredHeightsRef.current[index] = height;
  }, []);

  useEffect(() => {
    setFlatListReady(false);
    measuredHeightsRef.current = [];
    setUserScrolling(false);
  }, [currentSong?.id]);

  useEffect(() => {
    if (currentLineIndex >= 0 && !userScrolling && flatListReady) {
      requestAnimationFrame(() => {
        let offset = SCREEN_HEIGHT * 0.12;
        for (let i = 0; i < currentLineIndex; i++) {
          offset += measuredHeightsRef.current[i] ?? 48;
        }
        flatListRef.current?.scrollToOffset({ offset: Math.max(0, offset - TARGET_LINE_Y), animated: true });
      });
    }
  }, [currentLineIndex, flatListReady, userScrolling]);

  const renderLyricItem = useCallback(
    ({ item, index }: { item: LyricLine; index: number }) => (
      <LyricItem item={item} index={index} activeIndex={currentLineIndex} primaryColor={palette.primary} onMeasure={handleMeasure} />
    ),
    [currentLineIndex, palette.primary, handleMeasure],
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
      <FlatList
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
          paddingVertical: SCREEN_HEIGHT * 0.12,
        }}
        showsVerticalScrollIndicator={false}
      />
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

export const FullPlayer = React.memo(({ onClose }: FullPlayerProps = {}) => {
  const router = useRouter();
  const { top, bottom } = useSafeAreaInsets();
  const [coverError, setCoverError] = useState(false);
  const [pageIndex, setPageIndex] = useState(0);
  const [showQualityPicker, setShowQualityPicker] = useState(false);
  const [downloadingQuality, setDownloadingQuality] = useState<Quality | null>(null);
  const scrollRef = useRef<ScrollView>(null);
  const isSwipingRef = useRef(false);

  const currentSong = usePlayerStore((s) => s.currentSong);
  const isPlaying = usePlayerStore((s) => s.isPlaying);
  const setDuration = usePlayerStore((s) => s.setDuration);
  const addDownloadedId = usePlayerStore((s) => s.addDownloadedId);
  const addToQueue = usePlayerStore((s) => s.addToQueue);
  const addTask = useDownloadStore((s) => s.addTask);
  const updateProgress = useDownloadStore((s) => s.updateProgress);
  const completeTask = useDownloadStore((s) => s.completeTask);
  const failTask = useDownloadStore((s) => s.failTask);

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
      scrollRef.current.scrollTo({ x: pageIndex * SCREEN_WIDTH, animated: true });
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
            currentSong.lyric || '',
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
        const lyric = currentSong.lyric || await fetchLyric(currentSong.id, currentSong.title, currentSong.artist);
        const filePath = await downloadSong(
          currentSong.id, currentSong.title, currentSong.artist,
          currentSong.cover, currentSong.album, url, quality,
          (p) => updateProgress(currentSong.id, p * 100),
          lyric,
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
    const idx = Math.round(e.nativeEvent.contentOffset.x / SCREEN_WIDTH);
    setPageIndex(idx);
  }, []);

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
      <View style={{ flex: 1, paddingTop: top }}>

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

        <View style={{ height: PAGER_HEIGHT }}>
          <ScrollView
            ref={scrollRef}
            horizontal
            pagingEnabled
            showsHorizontalScrollIndicator={false}
            onScrollBeginDrag={() => { isSwipingRef.current = true; }}
            onScrollEndDrag={() => { setTimeout(() => { isSwipingRef.current = false; }, 100); }}
            onScroll={({ nativeEvent }) => {
              if (!isSwipingRef.current) return;
              const idx = Math.round(nativeEvent.contentOffset.x / SCREEN_WIDTH);
              if (idx !== pageIndex) setPageIndex(idx);
            }}
            scrollEventThrottle={16}
            onMomentumScrollEnd={handleMomentumEnd}
            bounces={false}
          >
            <View style={{ width: SCREEN_WIDTH, justifyContent: 'center', alignItems: 'center' }}>
              <View style={[styles.cover, styles.coverShadow]}>
                <View>
                  {coverError ? (
                    <View style={[styles.cover, styles.coverFallback]}>
                      <Ionicons name="musical-note" size={44} color="rgba(255,255,255,0.15)" />
                    </View>
                  ) : (
                    <Image source={{ uri: currentSong.cover }} style={styles.coverImage} onError={() => setCoverError(true)} />
                  )}
                </View>
              </View>
            </View>

            <View style={{ width: SCREEN_WIDTH }}>
              <PlayerLyricsList />
            </View>
          </ScrollView>
        </View>

        <View style={{ height: 12 }} />
        <View style={[styles.infoRow, { paddingHorizontal: screenPadding.horizontal }]}>
          <View style={{ flex: 1, overflow: 'hidden' }}>
            <MovingText text={currentSong.title} threshold={20} style={styles.title} />
          </View>
          <TouchableOpacity onPress={handleDownload} activeOpacity={0.7} style={{ padding: 4, marginLeft: 10 }}>
            <Ionicons name="download-outline" size={18} color="rgba(255,255,255,0.35)" />
          </TouchableOpacity>
        </View>
        {currentSong.artist ? (
          <Text style={[styles.artist, { paddingHorizontal: screenPadding.horizontal }]} numberOfLines={1}>{currentSong.artist}</Text>
        ) : null}

        <PlayerProgressBar />

        <PlayerControls />

        <View style={{ flex: 0.3 }} />

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
  coverShadow: {
    width: COVER_SIZE,
    height: COVER_SIZE,
    borderRadius: 16,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 10 },
    shadowOpacity: 0.45,
    shadowRadius: 24,
    elevation: 12,
  },
  coverImage: {
    width: COVER_SIZE,
    height: COVER_SIZE,
    borderRadius: 16,
  },
  coverFallback: {
    width: COVER_SIZE,
    height: COVER_SIZE,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: 'rgba(255,255,255,0.04)',
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
    marginTop: 8,
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

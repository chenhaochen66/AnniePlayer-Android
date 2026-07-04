import React, { useState, useCallback, useEffect, useRef, useMemo } from 'react';
import {
  View, Text, FlatList, Image, TouchableOpacity, ActivityIndicator,
  Dimensions, StyleSheet, BackHandler,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import Animated, { useSharedValue, useAnimatedStyle, withTiming, FadeInDown } from 'react-native-reanimated';
import { Ionicons } from '@expo/vector-icons';
import { usePlayerStore } from '../store/usePlayerStore';
import { useDownloadStore } from '../store/useDownloadStore';
import { useColorStore } from '../store/useColorStore';
import { useColorExtractor } from '../hooks/useColorExtractor';
import { useUIStore } from '../store/useUIStore';
import { getAlbumSongs } from '../services/albumApi';
import { fetchPlayUrl, fetchDownloadUrl, fetchLyric } from '../services/musicApi';
import { downloadSong } from '../services/downloadService';
import { QualityPicker } from '../components/QualityPicker';
import { enqueueDownload } from '../utils/downloadQueue';
import type { AlbumSong, Song, Quality } from '../types';

const SCREEN_H = Dimensions.get('window').height;
const ROW_H = 60;

const AlbumSongRow = React.memo(
  ({ item, index, selectMode, isSelected, task, onPlay, onDownload, onToggle, songs }: {
    item: AlbumSong; index: number; selectMode: boolean; isSelected: boolean;
    task: any; onPlay: any; onDownload: any; onToggle: any; songs: AlbumSong[];
  }) => {
    const minutes = Math.floor(item.duration / 60);
    const seconds = item.duration % 60;
    const downloadIndicator = task ? (
      task.status === 'completed' ? null : task.status === 'error' ? (
        <Ionicons name="alert-circle" size={16} color="#f87171" />
      ) : task.status === 'downloading' ? (
        <View style={{ alignItems: 'center', width: 36 }}>
          <View style={stylesMini.spinner} />
          <Text style={{ color: '#818cf8', fontSize: 8, marginTop: 1 }}>{Math.round(task.progress)}%</Text>
        </View>
      ) : (
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 2, paddingRight: 4 }}>
          <View style={stylesMini.pendingDot} />
          <Text style={{ color: 'rgba(255,255,255,0.25)', fontSize: 10 }}>等待中</Text>
        </View>
      )
    ) : null;
    return (
      <TouchableOpacity
        onPress={() => selectMode ? onToggle(item.id) : onPlay(item, songs)}
        activeOpacity={0.7}
        style={[stylesMini.row, isSelected && stylesMini.selected]}
      >
        {selectMode && (
          <Ionicons
            name={isSelected ? 'checkbox' : 'square-outline'}
            size={20}
            color={isSelected ? '#818cf8' : 'rgba(255,255,255,0.25)'}
            style={{ marginRight: 10 }}
          />
        )}
        <Text style={stylesMini.index}>{index + 1}</Text>
        <View style={{ flex: 1, marginLeft: 10 }}>
          <Text style={stylesMini.title} numberOfLines={1}>{item.name}</Text>
          <Text style={stylesMini.artist}>{item.artist_name}</Text>
        </View>
        {downloadIndicator || (
          !selectMode && (
            <TouchableOpacity onPress={() => onDownload(item)} activeOpacity={0.7} style={{ padding: 4 }}>
              <Ionicons name="download-outline" size={16} color="rgba(255,255,255,0.2)" />
            </TouchableOpacity>
          )
        )}
      </TouchableOpacity>
    );
  },
);

const stylesMini = StyleSheet.create({
  row: {
    flexDirection: 'row', alignItems: 'center',
    paddingVertical: 10, paddingHorizontal: 16, height: ROW_H,
    backgroundColor: 'transparent',
  },
  selected: {
    backgroundColor: 'rgba(129,140,248,0.08)',
  },
  index: {
    color: 'rgba(255,255,255,0.25)', fontSize: 12, width: 24, textAlign: 'center',
  },
  title: {
    color: '#fff', fontSize: 14, fontWeight: '500',
  },
  artist: {
    color: 'rgba(255,255,255,0.35)', fontSize: 11, marginTop: 1,
  },
  spinner: {
    width: 14, height: 14, borderRadius: 7,
    borderWidth: 2, borderColor: 'rgba(99,102,241,0.2)', borderTopColor: '#818cf8',
  },
  pendingDot: {
    width: 10, height: 10, borderRadius: 5,
    borderWidth: 2, borderColor: 'rgba(255,255,255,0.15)',
  },
});

export const AlbumDetailOverlay = React.memo(() => {
  const isOpen = useUIStore((s) => s.albumDetailVisible);
  const data = useUIStore((s) => s.albumDetailData);
  const onClose = useCallback(() => useUIStore.getState().setAlbumDetailVisible(false), []);

  const translateY = useSharedValue(SCREEN_H);

  useEffect(() => {
    translateY.value = withTiming(isOpen ? 0 : SCREEN_H, { duration: 150 });
  }, [isOpen]);

  useEffect(() => {
    if (!isOpen) return;
    const onBack = () => {
      if (selectModeRef.current) {
        setSelectMode(false);
        setSelectedIds(new Set());
        return true;
      }
      onClose();
      return true;
    };
    const sub = BackHandler.addEventListener('hardwareBackPress', onBack);
    return () => sub.remove();
  }, [isOpen, onClose]);

  const animatedStyle = useAnimatedStyle(() => ({ transform: [{ translateY: translateY.value }] }));

  const albumCover = data?.cover ?? '';
  const albumName = data?.name ?? '';
  const albumArtist = data?.artist ?? '';
  const id = data?.id ?? '';

  const [songs, setSongs] = useState<AlbumSong[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const setQueue = usePlayerStore((s) => s.setQueue);
  const addToQueue = usePlayerStore((s) => s.addToQueue);
  const setColors = useColorStore((s) => s.setColors);
  const setCurrentCoverUrl = useColorStore((s) => s.setCurrentCoverUrl);
  const addDownloadedId = usePlayerStore((s) => s.addDownloadedId);
  const addTask = useDownloadStore((s) => s.addTask);
  const updateProgress = useDownloadStore((s) => s.updateProgress);
  const completeTask = useDownloadStore((s) => s.completeTask);
  const failTask = useDownloadStore((s) => s.failTask);
  const { extractColors } = useColorExtractor();

  const downloadTasks = useDownloadStore((s) => s.tasks);

  const [selectMode, setSelectMode] = useState(false);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const selectModeRef = useRef(selectMode);
  selectModeRef.current = selectMode;
  const [batchDownloading, setBatchDownloading] = useState(false);
  const [batchProgress, setBatchProgress] = useState('');

  const [pickerVisible, setPickerVisible] = useState(false);
  const [pickerSong, setPickerSong] = useState<Song | null>(null);
  const [downloadingId, setDownloadingId] = useState<string | null>(null);

  useEffect(() => {
    if (!id) return;
    setLoading(true);
    setError('');
    setSongs([]);
    setSelectMode(false);
    setSelectedIds(new Set());
    setBatchDownloading(false);
    setBatchProgress('');
    setPickerVisible(false);
    setPickerSong(null);
    setDownloadingId(null);
    const timer = setTimeout(() => {
      getAlbumSongs(id)
        .then((list) => setSongs(list))
        .catch((e) => setError(e.message || '获取失败'))
        .finally(() => setLoading(false));
    }, 150);
    return () => clearTimeout(timer);
  }, [id]);

  const toSong = useCallback((s: AlbumSong): Song => ({
    id: s.id,
    title: s.name,
    artist: s.artist_name,
    cover: albumCover,
    album: albumName,
    duration: s.duration,
    platform: 'kuwo' as const,
    playUrl: '',
    lyric: undefined,
    qualities: ['128k', '320k', 'flac'] as Quality[],
  }), [albumCover, albumName]);

  const handlePlay = useCallback(async (albumSong: AlbumSong, allSongs: AlbumSong[]) => {
    const converted = allSongs.map(toSong);
    const idx = converted.findIndex((s) => s.id === albumSong.id);
    setQueue(converted, idx >= 0 ? idx : 0);
    setCurrentCoverUrl(albumCover);
    extractColors(albumCover).then((colors) => {
      if (colors.length >= 2) setColors({ primary: colors[0], secondary: colors[1] });
    }).catch(() => {});

    try {
      const [playUrl, lyric] = await Promise.all([
        fetchPlayUrl(albumSong.id),
        fetchLyric(albumSong.id, albumSong.name, albumSong.artist_name),
      ]);
      usePlayerStore.getState().updateSongPlayUrl(albumSong.id, playUrl);
      usePlayerStore.getState().updateSongLyric(albumSong.id, lyric);
    } catch {}
  }, [toSong, setQueue, extractColors, setColors, setCurrentCoverUrl, albumCover]);

  const handleSingleDownload = useCallback((albumSong: AlbumSong) => {
    const song = toSong(albumSong);
    setPickerSong(song);
    setPickerVisible(true);
  }, [toSong]);

  const handleDownloadWithQuality = useCallback((quality: Quality) => {
    if (!pickerSong) return;
    const song = pickerSong;
    setPickerVisible(false);
    enqueueDownload(async () => {
      setDownloadingId(song.id);
      addTask(song, quality);
      try {
        console.log('[album-dl] starting', song.id, quality, 'lyric existed:', !!song.lyric);
        const url = await fetchDownloadUrl(song.id, quality);
        const lyric = song.lyric || await fetchLyric(song.id, song.title, song.artist);
        console.log('[album-dl] lyric length:', lyric?.length || 0);
        const filePath = await downloadSong(
          song.id, song.title, song.artist,
          song.cover, song.album, url, quality,
          (p) => updateProgress(song.id, p * 100),
          lyric,
        );
        completeTask(song.id);
        useDownloadStore.getState().tasks[song.id].filePath = filePath;
        addDownloadedId(song.id);
        addToQueue(song);
      } catch {
        failTask(song.id);
      }
      setDownloadingId(null);
    });
  }, [pickerSong, addTask, updateProgress, completeTask, addDownloadedId, addToQueue, failTask]);

  const toggleSelect = useCallback((albumSongId: string) => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(albumSongId)) next.delete(albumSongId);
      else next.add(albumSongId);
      return next;
    });
  }, []);

  const handleBatchDownload = useCallback(async (quality: Quality) => {
    setPickerVisible(false);
    setBatchDownloading(true);
    const selected = songs.filter((s) => selectedIds.has(s.id));

    for (const s of selected) {
      const song = toSong(s);
      addTask(song, quality);
    }

    enqueueDownload(async () => {
      let success = 0;
      for (let i = 0; i < selected.length; i++) {
        const s = selected[i];
        const song = toSong(s);
        setBatchProgress(`${i + 1}/${selected.length} ${s.name}`);
        updateProgress(s.id, 0);
        try {
          const url = await fetchDownloadUrl(s.id, quality);
          const lyric = await fetchLyric(s.id, s.name, s.artist_name);
          await downloadSong(
            s.id, s.name, s.artist_name,
            albumCover, albumName, url, quality,
            (p) => updateProgress(s.id, p * 100),
            lyric,
          );
          completeTask(s.id);
          addDownloadedId(s.id);
          addToQueue(song);
          success++;
        } catch {
          failTask(s.id);
        }
      }
      setBatchDownloading(false);
      setBatchProgress(`下载完成 ${success}/${selected.length}`);
      setSelectMode(false);
      setSelectedIds(new Set());
    });
  }, [songs, selectedIds, toSong, addTask, updateProgress, completeTask, addDownloadedId, addToQueue, failTask, albumCover, albumName]);

  const allSelected = songs.length > 0 && selectedIds.size === songs.length;

  const handleToggleSelectAll = useCallback(() => {
    if (allSelected) {
      setSelectedIds(new Set());
    } else {
      setSelectedIds(new Set(songs.map((s) => s.id)));
    }
  }, [allSelected, songs]);

  const toggleSelectMode = useCallback(() => {
    setSelectMode((prev) => !prev);
    setSelectedIds(new Set());
  }, []);

  const getItemLayout = useCallback(
    (_: any, index: number) => ({ length: ROW_H, offset: ROW_H * index, index }),
    [],
  );

  const renderItem = useCallback(
    ({ item, index }: { item: AlbumSong; index: number }) => (
      <AlbumSongRow
        item={item}
        index={index}
        selectMode={selectMode}
        isSelected={selectedIds.has(item.id)}
        task={downloadTasks[item.id]}
        onPlay={handlePlay}
        onDownload={handleSingleDownload}
        onToggle={toggleSelect}
        songs={songs}
      />
    ),
    [selectMode, selectedIds, songs, handlePlay, handleSingleDownload, toggleSelect, downloadTasks],
  );

  return (
    <View style={{ position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, zIndex: 150 }} pointerEvents={isOpen ? 'auto' : 'none'}>
      {isOpen && (
        <TouchableOpacity
          activeOpacity={1}
          onPress={onClose}
          style={{ flex: 1, backgroundColor: 'rgba(0,0,0,0.5)' }}
        />
      )}
      <Animated.View style={[{ position: 'absolute', top: 0, left: 0, right: 0, bottom: 0 }, animatedStyle]}>
        <SafeAreaView style={{ flex: 1, backgroundColor: '#0a0a1a' }} edges={['top']}>
          <View style={{ flexDirection: 'row', alignItems: 'center', paddingHorizontal: 12, paddingTop: 40, paddingBottom: 4 }}>
            <TouchableOpacity onPress={onClose} activeOpacity={0.7} style={{ padding: 4 }}>
              <Ionicons name="chevron-back" size={22} color="rgba(255,255,255,0.7)" />
            </TouchableOpacity>
            <Text style={{ color: '#fff', fontSize: 16, fontWeight: '600', marginLeft: 8, flex: 1 }} numberOfLines={1}>
              专辑详情
            </Text>
            {selectMode && (
              <TouchableOpacity onPress={handleToggleSelectAll} activeOpacity={0.7} style={{ padding: 4, marginRight: 4 }}>
                <Text style={{ color: '#818cf8', fontSize: 13, fontWeight: '500' }}>
                  {allSelected ? '取消全选' : '全选'}
                </Text>
              </TouchableOpacity>
            )}
            <TouchableOpacity onPress={toggleSelectMode} activeOpacity={0.7} style={{ padding: 4 }}>
              <Text style={{ color: '#818cf8', fontSize: 13, fontWeight: '500' }}>
                {selectMode ? '取消' : '多选'}
              </Text>
            </TouchableOpacity>
          </View>

          {loading ? (
            <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center' }}>
              <ActivityIndicator color="#818cf8" />
            </View>
          ) : error ? (
            <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 24 }}>
              <Ionicons name="alert-circle-outline" size={36} color="rgba(255,255,255,0.2)" />
              <Text style={{ color: 'rgba(255,255,255,0.4)', fontSize: 14, marginTop: 12, textAlign: 'center' }}>
                {error}
              </Text>
            </View>
          ) : (
            <Animated.View entering={FadeInDown.duration(400)} key={songs.length} style={{ flex: 1 }}>
              <FlatList
                data={songs}
                renderItem={renderItem}
                keyExtractor={(item) => item.id}
                getItemLayout={getItemLayout}
                removeClippedSubviews
                initialNumToRender={12}
                maxToRenderPerBatch={10}
                windowSize={5}
                ListHeaderComponent={
                  <View style={{ flexDirection: 'row', paddingHorizontal: 16, paddingVertical: 16, alignItems: 'center' }}>
                    <Image
                      source={{ uri: albumCover }}
                      style={{ width: 100, height: 100, borderRadius: 10, backgroundColor: 'rgba(255,255,255,0.03)' }}
                    />
                    <View style={{ marginLeft: 14, flex: 1 }}>
                      <Text style={{ color: '#fff', fontSize: 17, fontWeight: '700' }} numberOfLines={2}>
                        {albumName}
                      </Text>
                      <Text style={{ color: 'rgba(255,255,255,0.5)', fontSize: 13, marginTop: 4 }}>
                        {albumArtist}
                      </Text>
                      <Text style={{ color: 'rgba(255,255,255,0.25)', fontSize: 11, marginTop: 2 }}>
                        {songs.length} 首歌曲
                      </Text>
                    </View>
                    {selectMode && selectedIds.size > 0 && (
                      <TouchableOpacity
                        onPress={() => setPickerVisible(true)}
                        disabled={batchDownloading}
                        activeOpacity={0.7}
                        style={{
                          backgroundColor: '#818cf8', borderRadius: 10, paddingVertical: 10, paddingHorizontal: 14,
                          alignItems: 'center', justifyContent: 'center', marginLeft: 8,
                          opacity: batchDownloading ? 0.5 : 1, minWidth: 80,
                        }}
                      >
                        {batchDownloading ? (
                          <View style={{ alignItems: 'center', gap: 2 }}>
                            <ActivityIndicator size="small" color="#fff" />
                            <Text style={{ color: '#fff', fontSize: 10, fontWeight: '500' }}>{batchProgress}</Text>
                          </View>
                        ) : (
                          <>
                            <Ionicons name="download" size={20} color="#fff" />
                            <Text style={{ color: '#fff', fontSize: 11, fontWeight: '600', marginTop: 2 }}>
                              {selectedIds.size}
                            </Text>
                          </>
                        )}
                      </TouchableOpacity>
                    )}
                  </View>
                }
                contentContainerStyle={{ paddingBottom: 120 }}
                showsVerticalScrollIndicator={false}
              />
            </Animated.View>
          )}

          <QualityPicker
            visible={pickerVisible}
            qualities={['flac', '320k', '128k']}
            onSelect={pickerSong ? handleDownloadWithQuality : (q) => handleBatchDownload(q)}
            onCancel={() => { setPickerVisible(false); setPickerSong(null); }}
          />

          {downloadingId && (
            <View style={{ position: 'absolute', bottom: 100, left: 20, right: 20, alignItems: 'center' }}>
              <View style={{ backgroundColor: 'rgba(26,26,46,0.95)', borderRadius: 12, paddingVertical: 10, paddingHorizontal: 20 }}>
                <Text style={{ color: '#818cf8', fontSize: 14, fontWeight: '600' }}>
                  正在下载 {songs.find((s) => s.id === downloadingId)?.name || '...'}
                </Text>
              </View>
            </View>
          )}
        </SafeAreaView>
      </Animated.View>
    </View>
  );
});

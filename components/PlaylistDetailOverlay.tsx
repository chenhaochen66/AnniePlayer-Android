import React, { useState, useCallback, useEffect, useRef } from 'react';
import {
  View, Text, FlatList, Image, TouchableOpacity, ActivityIndicator,
  useWindowDimensions, StyleSheet, BackHandler,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import Animated, { useSharedValue, useAnimatedStyle, withTiming, FadeInDown } from 'react-native-reanimated';
import { Ionicons } from '@expo/vector-icons';
import { useUIStore } from '../store/useUIStore';
import { usePlayerStore } from '../store/usePlayerStore';
import { useDownloadStore } from '../store/useDownloadStore';
import { fetchPlaylist } from '../services/playlistService';
import type { Song, Quality } from '../types';
import { fetchPlayUrlBySong, fetchDownloadUrlBySong, fetchLyric } from '../services/musicApi';
import { downloadSong } from '../services/downloadService';
import { enrichKuwoSong } from '../services/platforms/kuwoApi';
import { enrichQQSong } from '../services/platforms/qqApi';
import { enrichKuGouSong } from '../services/platforms/kugouApi';
import { QualityPicker } from './QualityPicker';
import { enqueueDownload } from '../utils/downloadQueue';
import { bestQuality } from '../utils/quality';

const ROW_H = 60;

const PlaylistSongRow = React.memo(({ item, index, selectMode, selected, onPlay, onDownload, onToggle }: {
  item: Song; index: number; selectMode: boolean; selected: boolean;
  onPlay: (song: Song) => void; onDownload: (song: Song) => void; onToggle: (id: string) => void;
}) => {
  const minutes = Math.floor(item.duration / 60);
  const seconds = item.duration % 60;
  return (
    <TouchableOpacity
      onPress={() => selectMode ? onToggle(item.id) : onPlay(item)}
      activeOpacity={0.7}
      style={[styles.row, selected && styles.selected]}
    >
      {selectMode && (
        <Ionicons
          name={selected ? 'checkbox' : 'square-outline'}
          size={20}
          color={selected ? '#818cf8' : 'rgba(255,255,255,0.25)'}
          style={{ marginRight: 10 }}
        />
      )}
      <Text style={styles.index}>{index + 1}</Text>
      <View style={{ flex: 1, marginLeft: 10 }}>
        <Text style={styles.title} numberOfLines={1}>{item.title}</Text>
        <Text style={styles.artist} numberOfLines={1}>
          {item.artist}{item.album ? ` · ${item.album}` : ''}
        </Text>
      </View>
      {!selectMode && (
        <TouchableOpacity onPress={() => onDownload(item)} activeOpacity={0.7} style={{ padding: 4 }}>
          <Ionicons name="download-outline" size={16} color="rgba(255,255,255,0.2)" />
        </TouchableOpacity>
      )}
    </TouchableOpacity>
  );
});

const styles = StyleSheet.create({
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
});

export const PlaylistDetailOverlay = React.memo(() => {
  const { height: winH } = useWindowDimensions();
  const visible = useUIStore((s) => s.playlistDetailVisible);
  const data = useUIStore((s) => s.playlistDetailData);
  const setVisible = useUIStore((s) => s.setPlaylistDetailVisible);

  const translateY = useSharedValue(winH);

  useEffect(() => {
    translateY.value = withTiming(visible ? 0 : winH, { duration: 150 });
  }, [visible, winH]);

  useEffect(() => {
    if (!visible) return;
    const onBack = () => {
      if (selectModeRef.current) {
        setSelectMode(false);
        setSelectedIds(new Set());
        return true;
      }
      setVisible(false);
      return true;
    };
    const sub = BackHandler.addEventListener('hardwareBackPress', onBack);
    return () => sub.remove();
  }, [visible, setVisible]);

  const animatedStyle = useAnimatedStyle(() => ({ transform: [{ translateY: translateY.value }] }));

  const [loading, setLoading] = useState(false);
  const [playlist, setPlaylist] = useState<{ name: string; creator: string; cover: string; songs: Song[] } | null>(null);
  const [error, setError] = useState('');
  const [selectMode, setSelectMode] = useState(false);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [batchPickerVisible, setBatchPickerVisible] = useState(false);
  const [batchDownloading, setBatchDownloading] = useState(false);

  const selectModeRef = useRef(selectMode);
  selectModeRef.current = selectMode;

  const setQueue = usePlayerStore((s) => s.setQueue);
  const addToQueue = usePlayerStore((s) => s.addToQueue);
  const addDownloadedId = usePlayerStore((s) => s.addDownloadedId);
  const addTask = useDownloadStore((s) => s.addTask);
  const updateProgress = useDownloadStore((s) => s.updateProgress);
  const completeTask = useDownloadStore((s) => s.completeTask);
  const failTask = useDownloadStore((s) => s.failTask);

  const [pickerVisible, setPickerVisible] = useState(false);
  const [pickerSong, setPickerSong] = useState<Song | null>(null);

  useEffect(() => {
    if (!visible || !data) return;
    setLoading(true);
    setError('');
    setPlaylist(null);
    setSelectMode(false);
    setSelectedIds(new Set());
    fetchPlaylist(data.platform, data.id).then((result) => {
      if (result) {
        setPlaylist({ name: result.name, creator: result.creator, cover: result.cover, songs: result.songs });
      } else {
        setError('获取歌单失败，请检查链接是否正确');
      }
    }).catch(() => {
      setError('网络请求失败');
    }).finally(() => setLoading(false));
  }, [visible, data]);

  const handlePlay = useCallback(async (song: Song) => {
    const songs = playlist?.songs || [];
    const idx = songs.findIndex((s) => s.id === song.id);
    if (idx < 0) return;
    if (song.platform === 'qq' || song.platform === 'kugou') {
      try {
        const url = await fetchPlayUrlBySong(song);
        const updated = songs.map((s) => s.id === song.id ? { ...s, playUrl: url } : s);
        setQueue(updated, idx);
      } catch {
        setQueue(songs, idx);
      }
    } else {
      setQueue(songs, idx);
    }
    try {
      const { lrc, tlyric } = await fetchLyric(song.id, song.title, song.artist);
      usePlayerStore.getState().updateSongLyric(song.id, lrc, tlyric ?? undefined);
    } catch {}
  }, [playlist, setQueue]);

  const handleDownload = useCallback((song: Song) => {
    setPickerSong(song);
    setPickerVisible(true);
  }, []);

  const handleDownloadWithQuality = useCallback(async (quality: Quality) => {
    if (!pickerSong) return;
    const song = pickerSong;
    setPickerVisible(false);
    setPickerSong(null);
    const sId = song.id;
    addTask(song, quality);
    enqueueDownload(sId, async () => {
      try {
        const url = await fetchDownloadUrlBySong(song, quality);
        const { lrc: lyric, tlyric } = await fetchLyric(sId, song.title, song.artist);
        const extra: any = song.platform === 'kuwo' ? await enrichKuwoSong(song.id, song.albumId || '') : song.platform === 'kugou' ? await enrichKuGouSong(song.id) : song.platform === 'qq' ? await enrichQQSong(song.id) : {};
        const filePath = await downloadSong(
          sId, song.title, song.artist,
          song.cover, song.album, url, quality,
          (p) => updateProgress(sId, p * 100),
          lyric, tlyric ?? undefined,
          song.albumArtist, song.trackNumber || extra.trackNumber, song.discNumber || extra.discNumber, song.releaseDate || extra.releaseDate,
        );
        completeTask(sId, filePath);
        addDownloadedId(sId);
        addToQueue(song);
      } catch {
        failTask(sId);
      }
    });
  }, [pickerSong, addTask, addToQueue, addDownloadedId, completeTask, failTask]);

  const handleToggleSelect = useCallback((id: string) => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  }, []);

  const handleSelectAll = useCallback(() => {
    if (!playlist) return;
    if (selectedIds.size === playlist.songs.length) {
      setSelectedIds(new Set());
    } else {
      setSelectedIds(new Set(playlist.songs.map((s) => s.id)));
    }
  }, [playlist, selectedIds]);

  const handleBatchDownload = useCallback((quality: Quality) => {
    if (!playlist) return;
    setBatchPickerVisible(false);
    setBatchDownloading(true);
    const songs = playlist.songs.filter((s) => selectedIds.has(s.id));
    const batchId = 'playlist_batch_' + Date.now();
    for (const s of songs) addTask(s, quality);
    enqueueDownload(batchId, async () => {
      for (const song of songs) {
        try {
          const actualQuality = bestQuality(quality, song.qualities);
          const url = await fetchDownloadUrlBySong(song, actualQuality);
          const { lrc: lyric, tlyric } = await fetchLyric(song.id, song.title, song.artist);
          const extra: any = song.platform === 'kuwo' ? await enrichKuwoSong(song.id, song.albumId || '') : song.platform === 'kugou' ? await enrichKuGouSong(song.id) : song.platform === 'qq' ? await enrichQQSong(song.id) : {};
          const filePath = await downloadSong(
            song.id, song.title, song.artist,
            song.cover, song.album, url, actualQuality,
            (p) => updateProgress(song.id, p * 100),
            lyric, tlyric ?? undefined,
            song.albumArtist, song.trackNumber || extra.trackNumber, song.discNumber || extra.discNumber, song.releaseDate || extra.releaseDate,
          );
          completeTask(song.id, filePath);
          addDownloadedId(song.id);
          addToQueue(song);
        } catch {
          failTask(song.id);
        }
      }
      setBatchDownloading(false);
      setSelectMode(false);
      setSelectedIds(new Set());
    });
  }, [playlist, selectedIds, addTask, addToQueue, addDownloadedId, completeTask, failTask]);

  const allSelected = playlist ? selectedIds.size === playlist.songs.length : false;
  const toggleSelectMode = useCallback(() => {
    setSelectMode((prev) => !prev);
    setSelectedIds(new Set());
  }, []);

  const getItemLayout = useCallback(
    (_: any, index: number) => ({ length: ROW_H, offset: ROW_H * index, index }),
    [],
  );

  return (
    <View style={{ position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, zIndex: 150, display: visible ? 'flex' : 'none' }}>
      {visible && (
        <TouchableOpacity
          activeOpacity={1}
          onPress={() => setVisible(false)}
          style={{ flex: 1, backgroundColor: 'rgba(0,0,0,0.5)' }}
        />
      )}
      <Animated.View style={[{ position: 'absolute', top: 0, left: 0, right: 0, bottom: 0 }, animatedStyle]}>
        <SafeAreaView style={{ flex: 1, backgroundColor: '#0a0a1a' }} edges={['top']}>
          <View style={{ flexDirection: 'row', alignItems: 'center', paddingHorizontal: 12, paddingTop: 40, paddingBottom: 4 }}>
            <TouchableOpacity onPress={() => setVisible(false)} activeOpacity={0.7} style={{ padding: 4 }}>
              <Ionicons name="chevron-back" size={22} color="rgba(255,255,255,0.7)" />
            </TouchableOpacity>
            <Text style={{ color: '#fff', fontSize: 16, fontWeight: '600', marginLeft: 8, flex: 1 }} numberOfLines={1}>
              歌单详情
            </Text>
            {!loading && playlist && (
              <>
                {selectMode && (
                  <TouchableOpacity onPress={handleSelectAll} activeOpacity={0.7} style={{ padding: 4, marginRight: 4 }}>
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
              </>
            )}
            {loading && <ActivityIndicator size="small" color="#818cf8" />}
          </View>

          {loading ? (
            <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center' }}>
              <ActivityIndicator size="large" color="#818cf8" />
            </View>
          ) : error ? (
            <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 24 }}>
              <Ionicons name="alert-circle-outline" size={36} color="rgba(255,255,255,0.2)" />
              <Text style={{ color: 'rgba(255,255,255,0.4)', fontSize: 14, marginTop: 12, textAlign: 'center' }}>
                {error}
              </Text>
            </View>
          ) : playlist ? (
            <Animated.View entering={FadeInDown.duration(400)} key={playlist.songs.length} style={{ flex: 1 }}>
              <FlatList
                data={playlist.songs}
                renderItem={({ item, index }) => (
                  <PlaylistSongRow
                    item={item} index={index}
                    selectMode={selectMode}
                    selected={selectedIds.has(item.id)}
                    onPlay={handlePlay}
                    onDownload={handleDownload}
                    onToggle={handleToggleSelect}
                  />
                )}
                keyExtractor={(item) => item.id}
                getItemLayout={getItemLayout}
                removeClippedSubviews
                initialNumToRender={12}
                maxToRenderPerBatch={10}
                windowSize={5}
                ListHeaderComponent={
                  <View style={{ flexDirection: 'row', paddingHorizontal: 16, paddingVertical: 16, alignItems: 'center' }}>
                    <Image
                      source={{ uri: playlist.cover || '' }}
                      style={{ width: 100, height: 100, borderRadius: 10, backgroundColor: 'rgba(255,255,255,0.03)' }}
                    />
                    <View style={{ marginLeft: 14, flex: 1 }}>
                      <Text style={{ color: '#fff', fontSize: 17, fontWeight: '700' }} numberOfLines={2}>
                        {playlist.name}
                      </Text>
                      <Text style={{ color: 'rgba(255,255,255,0.5)', fontSize: 13, marginTop: 4 }}>
                        {playlist.creator || ''}
                      </Text>
                      <Text style={{ color: 'rgba(255,255,255,0.25)', fontSize: 11, marginTop: 2 }}>
                        {playlist.songs.length} 首歌曲
                      </Text>
                    </View>
                    {selectMode && selectedIds.size > 0 && (
                      <TouchableOpacity
                        onPress={() => setBatchPickerVisible(true)}
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
          ) : null}

          <QualityPicker
            visible={pickerVisible}
            qualities={pickerSong?.qualities || ['128k']}
            onSelect={handleDownloadWithQuality}
            onCancel={() => { setPickerVisible(false); setPickerSong(null); }}
          />

          <QualityPicker
            visible={batchPickerVisible}
            qualities={['flac', '320k', '128k']}
            onSelect={handleBatchDownload}
            onCancel={() => setBatchPickerVisible(false)}
          />
        </SafeAreaView>
      </Animated.View>
    </View>
  );
});

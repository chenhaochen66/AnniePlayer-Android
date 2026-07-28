import React, { useState, useCallback, useEffect, useMemo } from 'react';
import {
  View, Text, FlatList, TouchableOpacity, ActivityIndicator,
  useWindowDimensions, BackHandler,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { Ionicons } from '@expo/vector-icons';
import { useUIStore } from '../store/useUIStore';
import { usePlayerStore } from '../store/usePlayerStore';
import { useDownloadStore } from '../store/useDownloadStore';
import { fetchPlaylist } from '../services/playlistService';
import type { Song, Quality } from '../types';
import { fetchPlayUrlBySong, fetchDownloadUrlBySong, fetchLyric } from '../services/musicApi';
import { downloadSong } from '../services/downloadService';
import { fetchAndCacheCover } from '../services/assetProxy';
import { enrichKuwoSong } from '../services/platforms/kuwoApi';
import { enrichQQSong } from '../services/platforms/qqApi';
import { enrichKuGouSong } from '../services/platforms/kugouApi';
import { QualityPicker } from './QualityPicker';
import { enqueueDownload } from '../utils/downloadQueue';

const ROW_H = 52;

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
      style={{
        flexDirection: 'row', alignItems: 'center',
        paddingHorizontal: 16, height: ROW_H,
      }}
    >
      {selectMode && (
        <Ionicons
          name={selected ? 'checkbox' : 'square-outline'}
          size={20}
          color={selected ? '#818cf8' : 'rgba(255,255,255,0.25)'}
          style={{ marginRight: 8 }}
        />
      )}
      <Text style={{ color: 'rgba(255,255,255,0.3)', fontSize: 13, width: selectMode ? 20 : 28, textAlign: 'center' }}>
        {index + 1}
      </Text>
      <View style={{ flex: 1, marginLeft: 10 }}>
        <Text style={{ color: '#fff', fontSize: 14, fontWeight: '500' }} numberOfLines={1}>
          {item.title}
        </Text>
        <Text style={{ color: 'rgba(255,255,255,0.35)', fontSize: 12, marginTop: 1 }} numberOfLines={1}>
          {item.artist}
          {item.album ? ` · ${item.album}` : ''}
        </Text>
      </View>
      <Text style={{ color: 'rgba(255,255,255,0.25)', fontSize: 12, marginRight: 8 }}>
        {String(minutes).padStart(2, '0')}:{String(seconds).padStart(2, '0')}
      </Text>
      {!selectMode && (
        <TouchableOpacity onPress={() => onDownload(item)} activeOpacity={0.7} style={{ padding: 4 }}>
          <Ionicons name="download-outline" size={18} color="rgba(255,255,255,0.3)" />
        </TouchableOpacity>
      )}
    </TouchableOpacity>
  );
});

export const PlaylistDetailOverlay = React.memo(() => {
  const visible = useUIStore((s) => s.playlistDetailVisible);
  const data = useUIStore((s) => s.playlistDetailData);
  const setVisible = useUIStore((s) => s.setPlaylistDetailVisible);

  const [loading, setLoading] = useState(false);
  const [playlist, setPlaylist] = useState<{ name: string; creator: string; songs: Song[] } | null>(null);
  const [error, setError] = useState('');
  const [selectMode, setSelectMode] = useState(false);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [batchPickerVisible, setBatchPickerVisible] = useState(false);
  const [batchDownloading, setBatchDownloading] = useState(false);

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
        setPlaylist({ name: result.name, creator: result.creator, songs: result.songs });
      } else {
        setError('获取歌单失败，请检查链接是否正确');
      }
    }).catch(() => {
      setError('网络请求失败');
    }).finally(() => setLoading(false));
  }, [visible, data]);

  useEffect(() => {
    if (!visible) return;
    const handler = BackHandler.addEventListener('hardwareBackPress', () => {
      setVisible(false);
      return true;
    });
    return () => handler.remove();
  }, [visible, setVisible]);

  const handlePlay = useCallback(async (song: Song) => {
    const songs = playlist?.songs || [];
    const idx = songs.findIndex((s) => s.id === song.id);
    if (idx < 0) return;
    if (song.platform === 'qq' || song.platform === 'kugou') {
      try {
        const url = await fetchPlayUrlBySong(song);
        const updated = songs.map((s) => s.id === song.id ? { ...s, playUrl: url } : s);
        setQueue(updated, idx);
        return;
      } catch {}
    }
    setQueue(songs, idx);
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
          () => {},
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
          const url = await fetchDownloadUrlBySong(song, quality);
          const { lrc: lyric, tlyric } = await fetchLyric(song.id, song.title, song.artist);
          const extra: any = song.platform === 'kuwo' ? await enrichKuwoSong(song.id, song.albumId || '') : song.platform === 'kugou' ? await enrichKuGouSong(song.id) : song.platform === 'qq' ? await enrichQQSong(song.id) : {};
          const filePath = await downloadSong(
            song.id, song.title, song.artist,
            song.cover, song.album, url, quality,
            () => {},
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

  if (!visible) return null;

  return (
    <View style={{ position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, backgroundColor: '#0a0a1a', zIndex: 100 }}>
      <SafeAreaView style={{ flex: 1 }}>
        <View style={{ flexDirection: 'row', alignItems: 'center', paddingHorizontal: 16, paddingTop: 8, paddingBottom: 12 }}>
          <TouchableOpacity onPress={() => setVisible(false)} activeOpacity={0.7} style={{ padding: 4 }}>
            <Ionicons name="close" size={24} color="#fff" />
          </TouchableOpacity>
          <Text style={{ color: '#fff', fontSize: 18, fontWeight: '700', marginLeft: 12, flex: 1 }} numberOfLines={1}>
            {data?.name || '歌单'}
          </Text>
          {!loading && playlist && (
            <TouchableOpacity onPress={() => { setSelectMode(!selectMode); if (selectMode) setSelectedIds(new Set()); }} activeOpacity={0.7}>
              <Text style={{ color: selectMode ? '#818cf8' : 'rgba(255,255,255,0.5)', fontSize: 14, fontWeight: '600' }}>
                {selectMode ? '完成' : '选择'}
              </Text>
            </TouchableOpacity>
          )}
          {loading && <ActivityIndicator size="small" color="#818cf8" />}
        </View>

        {loading && (
          <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center' }}>
            <ActivityIndicator size="large" color="#818cf8" />
            <Text style={{ color: 'rgba(255,255,255,0.3)', fontSize: 14, marginTop: 12 }}>正在获取歌单...</Text>
          </View>
        )}

        {error ? (
          <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center' }}>
            <Ionicons name="alert-circle-outline" size={48} color="rgba(255,255,255,0.2)" />
            <Text style={{ color: 'rgba(255,255,255,0.4)', fontSize: 14, marginTop: 12 }}>{error}</Text>
          </View>
        ) : null}

        {playlist && (
          <>
            <View style={{ flexDirection: 'row', paddingHorizontal: 16, paddingBottom: 12, alignItems: 'center' }}>
              <View style={{ flex: 1 }}>
                <Text style={{ color: '#fff', fontSize: 20, fontWeight: '700' }}>{playlist.name}</Text>
                {playlist.creator ? (
                  <Text style={{ color: 'rgba(255,255,255,0.4)', fontSize: 13, marginTop: 2 }}>
                    {playlist.creator} · {playlist.songs.length} 首
                  </Text>
                ) : (
                  <Text style={{ color: 'rgba(255,255,255,0.4)', fontSize: 13, marginTop: 2 }}>
                    {playlist.songs.length} 首
                  </Text>
                )}
              </View>
            </View>

            {selectMode && (
              <View style={{
                flexDirection: 'row', alignItems: 'center', gap: 8,
                backgroundColor: 'rgba(129,140,248,0.08)', borderRadius: 14,
                paddingVertical: 10, paddingHorizontal: 14, marginHorizontal: 16, marginBottom: 8,
              }}>
                <TouchableOpacity onPress={handleSelectAll} activeOpacity={0.7} style={{ flexDirection: 'row', alignItems: 'center', gap: 6, flex: 1 }}>
                  <Ionicons name={selectedIds.size === playlist.songs.length ? 'checkbox' : 'square-outline'} size={20} color="#818cf8" />
                  <Text style={{ color: 'rgba(255,255,255,0.7)', fontSize: 13, fontWeight: '500' }}>
                    {selectedIds.size === playlist.songs.length ? '取消全选' : `全选 (${playlist.songs.length})`}
                  </Text>
                </TouchableOpacity>
                <TouchableOpacity
                  onPress={() => setBatchPickerVisible(true)}
                  disabled={selectedIds.size === 0 || batchDownloading}
                  activeOpacity={0.7}
                  style={{
                    backgroundColor: selectedIds.size > 0 ? '#818cf8' : 'rgba(255,255,255,0.1)',
                    borderRadius: 10, paddingVertical: 8, paddingHorizontal: 16,
                    opacity: batchDownloading ? 0.5 : 1,
                  }}
                >
                  <Text style={{ color: '#fff', fontSize: 12, fontWeight: '600' }}>
                    {batchDownloading ? '下载中...' : `下载 (${selectedIds.size})`}
                  </Text>
                </TouchableOpacity>
              </View>
            )}

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
              contentContainerStyle={{ paddingBottom: 40 }}
              showsVerticalScrollIndicator={false}
            />
          </>
        )}

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
    </View>
  );
});

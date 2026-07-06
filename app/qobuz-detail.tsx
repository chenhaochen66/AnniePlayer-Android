import React, { useState, useCallback, useEffect, useRef, useMemo } from 'react';
import { View, Text, FlatList, Image, TouchableOpacity, ActivityIndicator, StyleSheet } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { Ionicons } from '@expo/vector-icons';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { usePlayerStore } from '../store/usePlayerStore';
import { useDownloadStore } from '../store/useDownloadStore';
import { useColorStore } from '../store/useColorStore';
import { useColorExtractor } from '../hooks/useColorExtractor';
import { useQobuzStore } from '../store/useQobuzStore';
import { getTrack, getAlbum, getFileUrl, mapTrackToQobuzTrack, mapAlbumToQobuzAlbum } from '../services/qobuzApi';
import { downloadSong } from '../services/downloadService';
import { enqueueDownload } from '../utils/downloadQueue';
import { screenPadding } from '../constants/tokens';
import type { QobuzTrack } from '../types';

const ROW_H = 60;

const TrackRow = React.memo(({ item, index, selectMode, isSelected, downloadingId, onPlay, onDownload, onToggle }: {
  item: QobuzTrack; index: number; selectMode: boolean; isSelected: boolean;
  downloadingId: string | null;
  onPlay: (t: QobuzTrack) => void; onDownload: (t: QobuzTrack) => void; onToggle: (id: string) => void;
}) => (
  <TouchableOpacity
    onPress={() => selectMode ? onToggle(item.id) : onPlay(item)}
    activeOpacity={0.7}
    style={[styles.row, isSelected && styles.selected]}
  >
    {selectMode && (
      <Ionicons
        name={isSelected ? 'checkbox' : 'square-outline'}
        size={20}
        color={isSelected ? '#818cf8' : 'rgba(255,255,255,0.25)'}
        style={{ marginRight: 10 }}
      />
    )}
    <Text style={styles.index}>{index + 1}</Text>
    <View style={{ flex: 1, marginLeft: 10 }}>
      <Text style={styles.title} numberOfLines={1}>{item.title}</Text>
    </View>
    {downloadingId === item.id ? (
      <ActivityIndicator size="small" color="#818cf8" />
    ) : (
      !selectMode && (
        <TouchableOpacity onPress={() => onDownload(item)} activeOpacity={0.7} style={{ padding: 4 }}>
          <Ionicons name="download-outline" size={16} color="rgba(255,255,255,0.2)" />
        </TouchableOpacity>
      )
    )}
  </TouchableOpacity>
));

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row', alignItems: 'center',
    paddingVertical: 10, paddingHorizontal: screenPadding.horizontal,
    height: ROW_H, backgroundColor: 'transparent',
    borderBottomWidth: 1, borderBottomColor: 'rgba(255,255,255,0.04)',
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
});

export default function QobuzDetailPage() {
  const { type, id } = useLocalSearchParams<{ type: string; id: string }>();
  const router = useRouter();
  const closing = useRef(false);
  const [tracks, setTracks] = useState<QobuzTrack[]>([]);
  const [cover, setCover] = useState('');
  const [title, setTitle] = useState('');
  const [artist, setArtist] = useState('');
  const [year, setYear] = useState<number | undefined>();
  const [label, setLabel] = useState<string | undefined>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [downloadingId, setDownloadingId] = useState<string | null>(null);
  const [selectMode, setSelectMode] = useState(false);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [batchDownloading, setBatchDownloading] = useState(false);
  const [batchProgress, setBatchProgress] = useState('');

  const setQueue = usePlayerStore((s) => s.setQueue);
  const addToQueue = usePlayerStore((s) => s.addToQueue);
  const setColors = useColorStore((s) => s.setColors);
  const setCurrentCoverUrl = useColorStore((s) => s.setCurrentCoverUrl);
  const addDownloadedId = usePlayerStore((s) => s.addDownloadedId);
  const addTask = useDownloadStore((s) => s.addTask);
  const updateProgress = useDownloadStore((s) => s.updateProgress);
  const completeTask = useDownloadStore((s) => s.completeTask);
  const failTask = useDownloadStore((s) => s.failTask);
  const getActiveToken = useQobuzStore((s) => s.getActiveToken);
  const { extractColors } = useColorExtractor();

  const handleBack = useCallback(() => {
    if (closing.current) return;
    closing.current = true;
    setTimeout(() => router.back(), 200);
  }, [router]);

  useEffect(() => {
    if (!type || !id) { setError('缺少参数'); setLoading(false); return; }
    const token = getActiveToken();
    if (!token) { setError('请先在设置中配置并选择 Qobuz Token'); setLoading(false); return; }
    setLoading(true);
    setError('');
    (async () => {
      try {
        if (type === 'track') {
          const raw = await getTrack(id, token);
          const t = mapTrackToQobuzTrack(raw);
          setTracks([t]);
          setCover(t.cover);
          setTitle(t.title);
          setArtist(t.artist);
          setYear(t.year);
          setLabel(t.label);
        } else {
          const raw = await getAlbum(id, token);
          const a = mapAlbumToQobuzAlbum(raw);
          setTracks(a.tracks);
          setCover(a.cover);
          setTitle(a.title);
          setArtist(a.artist);
          setYear(a.year);
          setLabel(a.label);
        }
      } catch (e: any) {
        setError(e.message || '获取失败');
      } finally {
        setLoading(false);
      }
    })();
  }, [type, id, getActiveToken]);

  const handlePlay = useCallback((track: QobuzTrack) => {
    const token = getActiveToken();
    if (!token) { return; }
    const allTracks = tracks.length > 1 ? tracks : [track];
    const idx = allTracks.findIndex((t) => t.id === track.id);
    setCurrentCoverUrl(track.cover);
    extractColors(track.cover).then((colors) => {
      if (colors.length >= 2) setColors({ primary: colors[0], secondary: colors[1] });
    }).catch(() => {});
    const songs: any[] = allTracks.map((t) => ({
      id: `qobuz:${t.id}`, title: t.title, artist: t.artist,
      cover: t.cover, album: t.album, duration: t.duration,
      platform: 'qobuz' as const, qobuzToken: token,
    }));
    setQueue(songs, idx >= 0 ? idx : 0);
  }, [getActiveToken, tracks, extractColors, setColors, setCurrentCoverUrl, setQueue]);

  const handleDownloadTrack = useCallback((track: QobuzTrack) => {
    const token = getActiveToken();
    if (!token) { return; }
    setDownloadingId(track.id);
    const sId = `qobuz:${track.id}`;
    const song: any = {
      id: sId, title: track.title, artist: track.artist,
      cover: track.cover, album: track.album,
      platform: 'qobuz' as const,
    };
    addTask(song, 'flac');
    enqueueDownload(async () => {
      try {
        const info = await getFileUrl(track.id, 27, token);
        const filePath = await downloadSong(
          sId, track.title, track.artist,
          track.cover, track.album, info.url, 'flac',
          (p) => updateProgress(sId, p * 100), '',
        );
        completeTask(sId, filePath);
        addDownloadedId(sId);
        addToQueue(song);
      } catch (e) {
        failTask(sId);
      }
      setDownloadingId(null);
    });
  }, [getActiveToken, addTask, updateProgress, completeTask, addDownloadedId, addToQueue, failTask]);

  const toggleSelectMode = useCallback(() => {
    setSelectMode((prev) => !prev);
    setSelectedIds(new Set());
  }, []);

  const toggleSelect = useCallback((trackId: string) => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(trackId)) next.delete(trackId);
      else next.add(trackId);
      return next;
    });
  }, []);

  const handleSelectAll = useCallback(() => {
    setSelectedIds((prev) => {
      if (prev.size === tracks.length) return new Set();
      return new Set(tracks.map((t) => t.id));
    });
  }, [tracks]);

  const handleBatchDownload = useCallback(() => {
    const token = getActiveToken();
    if (!token) { return; }
    setBatchDownloading(true);
    const selected = tracks.filter((t) => selectedIds.has(t.id));
    for (const t of selected) {
      const song: any = {
        id: `qobuz:${t.id}`, title: t.title, artist: t.artist,
        cover: t.cover, album: t.album, platform: 'qobuz' as const,
      };
      addTask(song, 'flac');
    }
    enqueueDownload(async () => {
      let success = 0;
      for (let i = 0; i < selected.length; i++) {
        const t = selected[i];
        const sId = `qobuz:${t.id}`;
        setBatchProgress(`${i + 1}/${selected.length} ${t.title}`);
        updateProgress(sId, 0);
        try {
          const info = await getFileUrl(t.id, 27, token);
          const filePath = await downloadSong(
            sId, t.title, t.artist,
            t.cover, t.album, info.url, 'flac',
            (p) => updateProgress(sId, p * 100), '',
          );
          completeTask(sId, filePath);
          addDownloadedId(sId);
          addToQueue({
            id: sId, title: t.title, artist: t.artist,
            cover: t.cover, album: t.album, platform: 'qobuz',
          } as any);
          success++;
        } catch {
          failTask(sId);
        }
      }
      setBatchDownloading(false);
      setBatchProgress(`下载完成 ${success}/${selected.length}`);
      setSelectMode(false);
      setSelectedIds(new Set());
    });
  }, [tracks, selectedIds, getActiveToken, addTask, updateProgress, completeTask, addDownloadedId, addToQueue, failTask]);

  const getItemLayout = useCallback(
    (_: any, index: number) => ({ length: ROW_H, offset: ROW_H * index, index }),
    [],
  );

  const renderItem = useCallback(
    ({ item, index }: { item: QobuzTrack; index: number }) => (
      <TrackRow
        item={item} index={index}
        selectMode={selectMode} isSelected={selectedIds.has(item.id)}
        downloadingId={downloadingId}
        onPlay={handlePlay} onDownload={handleDownloadTrack} onToggle={toggleSelect}
      />
    ),
    [selectMode, selectedIds, downloadingId, handlePlay, handleDownloadTrack, toggleSelect],
  );

  const headerComponent = useMemo(() => (
    <View style={{ flexDirection: 'row', paddingHorizontal: screenPadding.horizontal, paddingVertical: 16, alignItems: 'center' }}>
      <Image
        source={{ uri: cover }}
        style={{ width: 100, height: 100, borderRadius: 10, backgroundColor: 'rgba(255,255,255,0.03)' }}
      />
      <View style={{ marginLeft: 14, flex: 1 }}>
        <Text style={{ color: '#fff', fontSize: 17, fontWeight: '700' }} numberOfLines={2}>{title}</Text>
        <Text style={{ color: 'rgba(255,255,255,0.5)', fontSize: 13, marginTop: 4 }}>{artist}</Text>
        <View style={{ flexDirection: 'row', gap: 8, marginTop: 2 }}>
          {year ? <Text style={{ color: 'rgba(255,255,255,0.25)', fontSize: 11 }}>{year}</Text> : null}
          {label ? <Text style={{ color: 'rgba(255,255,255,0.25)', fontSize: 11 }}>{String(label)}</Text> : null}
          <Text style={{ color: 'rgba(255,255,255,0.25)', fontSize: 11 }}>{tracks.length} 首</Text>
        </View>
      </View>
      {selectMode && selectedIds.size > 0 && (
        <TouchableOpacity
          onPress={handleBatchDownload}
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
              <Text style={{ color: '#fff', fontSize: 11, fontWeight: '600', marginTop: 2 }}>{selectedIds.size}</Text>
            </>
          )}
        </TouchableOpacity>
      )}
    </View>
  ), [cover, title, artist, year, label, tracks.length, selectMode, selectedIds, batchDownloading, batchProgress, handleBatchDownload]);

  return (
    <SafeAreaView style={{ flex: 1, backgroundColor: '#0a0a1a' }} edges={['top']}>
      <View style={{ flex: 1 }}>
        <View style={{
          flexDirection: 'row', alignItems: 'center',
          paddingHorizontal: screenPadding.horizontal,
          paddingTop: 40, paddingBottom: 8,
        }}>
          <TouchableOpacity onPress={handleBack} activeOpacity={0.7} style={{ padding: 4, marginRight: 8 }}>
            <Ionicons name="arrow-back" size={22} color="rgba(255,255,255,0.7)" />
          </TouchableOpacity>
          <Text style={{ color: '#fff', fontSize: 20, fontWeight: '700', flex: 1 }}>
            {type === 'album' ? 'Qobuz专辑详情' : 'Qobuz单曲详情'}
          </Text>
          {type === 'album' && tracks.length > 1 && (
            <TouchableOpacity onPress={toggleSelectMode} activeOpacity={0.7} style={{ padding: 4, marginRight: 4 }}>
              <Text style={{ color: '#818cf8', fontSize: 13, fontWeight: '500' }}>
                {selectMode ? '取消' : '选择'}
              </Text>
            </TouchableOpacity>
          )}
          {selectMode && (
            <TouchableOpacity onPress={handleSelectAll} activeOpacity={0.7} style={{ padding: 4 }}>
              <Text style={{ color: '#818cf8', fontSize: 13, fontWeight: '500' }}>
                {selectedIds.size === tracks.length ? '取消全选' : '全选'}
              </Text>
            </TouchableOpacity>
          )}
        </View>

        {loading ? (
          <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center' }}>
            <ActivityIndicator color="#818cf8" />
          </View>
        ) : error ? (
          <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 24 }}>
            <Ionicons name="alert-circle-outline" size={36} color="rgba(255,255,255,0.2)" />
            <Text style={{ color: 'rgba(255,255,255,0.4)', fontSize: 14, marginTop: 12, textAlign: 'center' }}>{error}</Text>
          </View>
        ) : (
          <FlatList
            data={tracks}
            renderItem={renderItem}
            keyExtractor={(item) => item.id}
            getItemLayout={getItemLayout}
            removeClippedSubviews
            initialNumToRender={12}
            maxToRenderPerBatch={10}
            windowSize={5}
            ListHeaderComponent={headerComponent}
            contentContainerStyle={{ paddingBottom: 120 }}
            showsVerticalScrollIndicator={false}
          />
        )}
      </View>
    </SafeAreaView>
  );
}

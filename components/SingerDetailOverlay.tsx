import React, { useState, useCallback, useEffect, useRef, useMemo } from 'react';
import { View, Text, FlatList, Image, TouchableOpacity, Dimensions, StyleSheet, BackHandler } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import Animated, { useSharedValue, useAnimatedStyle, withTiming, FadeInDown } from 'react-native-reanimated';
import { Ionicons } from '@expo/vector-icons';
import { usePlayerStore } from '../store/usePlayerStore';
import { useUIStore } from '../store/useUIStore';
import { useDownloadStore } from '../store/useDownloadStore';
import { useColorStore } from '../store/useColorStore';
import { useColorExtractor } from '../hooks/useColorExtractor';
import { getArtistSongs, getArtistAlbums } from '../services/artistApi';
import { fetchPlayUrl, fetchDownloadUrl, fetchLyric } from '../services/musicApi';
import { downloadSong } from '../services/downloadService';
import { AlbumCard } from './AlbumCard';
import { QualityPicker } from './QualityPicker';
import { SkeletonLoader } from './SkeletonLoader';
import { enqueueDownload } from '../utils/downloadQueue';
import type { Song, Album, Quality } from '../types';

const SCREEN_H = Dimensions.get('window').height;
const ROW_H = 60;
const ALBUM_GAP = 10;
const SCREEN_W = Dimensions.get('window').width;
const ALBUM_CARD_W = (SCREEN_W - 8 * 2 - ALBUM_GAP) / 2;
const ALBUM_CARD_H = Math.round(ALBUM_CARD_W + 68);

const SongRow = React.memo(({ item, index, activeSongId, downloadingId, downloadTask, onPlay, onDownload }: {
  item: Song; index: number; activeSongId?: string; downloadingId: string | null;
  downloadTask: any; onPlay: (song: Song) => void; onDownload: (song: Song) => void;
}) => {
  const isActive = activeSongId === item.id;
  const isDling = downloadingId === item.id || downloadTask?.status === 'downloading' || downloadTask?.status === 'pending';
  return (
    <TouchableOpacity onPress={() => onPlay(item)} activeOpacity={0.7}>
      <View style={[styles.row, isActive && styles.activeRow]}>
        <Text style={styles.index}>{index + 1}</Text>
        <View style={{ width: 40, height: 40, borderRadius: 6, backgroundColor: 'rgba(255,255,255,0.03)', overflow: 'hidden', alignItems: 'center', justifyContent: 'center', marginLeft: 8 }}>
          {item.cover ? (
            <Image source={{ uri: item.cover }} style={{ width: 40, height: 40 }} />
          ) : (
            <Ionicons name="musical-note" size={16} color="rgba(255,255,255,0.15)" />
          )}
          {isActive && <View style={{ position: 'absolute' }}><Ionicons name="play" size={14} color="#818cf8" /></View>}
        </View>
        <View style={{ flex: 1, marginLeft: 10, marginRight: 4 }}>
          <Text style={[styles.title, isActive && { color: '#818cf8' }]} numberOfLines={1}>{item.title}</Text>
          <Text style={styles.artist} numberOfLines={1}>{item.artist}</Text>
        </View>
        <TouchableOpacity onPress={() => onDownload(item)} activeOpacity={0.7} style={{ padding: 6 }}>
          {isDling ? (
            <View style={styles.spinner} />
          ) : (
            <Ionicons name="download-outline" size={16} color="rgba(255,255,255,0.25)" />
          )}
        </TouchableOpacity>
      </View>
    </TouchableOpacity>
  );
});

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row', alignItems: 'center',
    paddingVertical: 8, paddingHorizontal: 12, height: ROW_H,
  },
  activeRow: { backgroundColor: 'rgba(129,140,248,0.06)' },
  index: { color: 'rgba(255,255,255,0.2)', fontSize: 12, width: 22, textAlign: 'center' },
  title: { color: '#fff', fontSize: 14, fontWeight: '500' },
  artist: { color: 'rgba(255,255,255,0.35)', fontSize: 11, marginTop: 1 },
  spinner: {
    width: 14, height: 14, borderRadius: 7,
    borderWidth: 2, borderColor: 'rgba(99,102,241,0.2)', borderTopColor: '#818cf8',
  },
  tab: {
    flex: 1, height: 34, borderRadius: 8,
    alignItems: 'center', justifyContent: 'center',
  },
  tabText: { fontSize: 13, fontWeight: '600' },
  pageBtn: {
    width: 32, height: 32, borderRadius: 8,
    alignItems: 'center', justifyContent: 'center',
    backgroundColor: 'rgba(255,255,255,0.05)',
  },
  pageBtnActive: { backgroundColor: '#818cf8' },
  pageText: { color: 'rgba(255,255,255,0.5)', fontSize: 13, fontWeight: '500' },
  pageTextActive: { color: '#fff' },
});

function getPageRange(current: number, total: number): (number | 'ellipsis')[] {
  if (total <= 7) return Array.from({ length: total }, (_, i) => i + 1);
  const r: (number | 'ellipsis')[] = [];
  if (current <= 3) {
    for (let i = 1; i <= Math.min(4, total); i++) r.push(i);
    if (total > 4) { r.push('ellipsis'); r.push(total); }
  } else if (current >= total - 2) {
    r.push(1); r.push('ellipsis');
    for (let i = total - 3; i <= total; i++) r.push(i);
  } else {
    r.push(1); r.push('ellipsis');
    r.push(current - 1); r.push(current); r.push(current + 1);
    r.push('ellipsis'); r.push(total);
  }
  return r;
}

export const SingerDetailOverlay = React.memo(() => {
  const isOpen = useUIStore((s) => s.singerDetailVisible);
  const data = useUIStore((s) => s.singerDetailData);
  const albumOpen = useUIStore((s) => s.albumDetailVisible);
  const onClose = useCallback(() => useUIStore.getState().setSingerDetailVisible(false), []);

  const translateY = useSharedValue(SCREEN_H);

  useEffect(() => {
    translateY.value = withTiming(isOpen ? 0 : SCREEN_H, { duration: 150 });
  }, [isOpen]);

  useEffect(() => {
    if (!isOpen) return;
    const onBack = () => { onClose(); return true; };
    const sub = BackHandler.addEventListener('hardwareBackPress', onBack);
    return () => sub.remove();
  }, [isOpen, onClose]);

  const animatedStyle = useAnimatedStyle(() => ({ transform: [{ translateY: translateY.value }] }));

  const artistName = data?.name ?? '';
  const artistPic = data?.picture ?? '';
  const id = data?.id ?? '';

  const [tab, setTab] = useState<'song' | 'album'>('song');

  const [songs, setSongs] = useState<Song[]>([]);
  const [songTotal, setSongTotal] = useState(0);
  const [songPage, setSongPage] = useState(1);
  const [songLoading, setSongLoading] = useState(false);
  const [songError, setSongError] = useState('');

  const [albums, setAlbums] = useState<Album[]>([]);
  const [albumLoading, setAlbumLoading] = useState(false);

  const setQueue = usePlayerStore((s) => s.setQueue);
  const addToQueue = usePlayerStore((s) => s.addToQueue);
  const setColors = useColorStore((s) => s.setColors);
  const setCurrentCoverUrl = useColorStore((s) => s.setCurrentCoverUrl);
  const addDownloadedId = usePlayerStore((s) => s.addDownloadedId);
  const addTask = useDownloadStore((s) => s.addTask);
  const updateProgress = useDownloadStore((s) => s.updateProgress);
  const completeTask = useDownloadStore((s) => s.completeTask);
  const failTask = useDownloadStore((s) => s.failTask);
  const downloadTasks = useDownloadStore((s) => s.tasks);
  const { extractColors } = useColorExtractor();

  const [pickerVisible, setPickerVisible] = useState(false);
  const [pickerSong, setPickerSong] = useState<Song | null>(null);
  const [downloadingId, setDownloadingId] = useState<string | null>(null);
  const [albumSortAsc, setAlbumSortAsc] = useState(false);

  const activeSongId = usePlayerStore((s) => s.currentSong?.id);

  useEffect(() => {
    if (!isOpen || !id) { setSongs([]); setAlbums([]); setSongPage(1); return; }
    setSongPage(1);
    setSongs([]);
    setAlbums([]);
    setTab('song');
    setSongLoading(true);
    setSongError('');
    getArtistSongs(id, 1)
      .then(({ songs: list, total }) => {
        setSongs(list);
        setSongTotal(total);
      })
      .catch((e) => setSongError(e.message || '获取失败'))
      .finally(() => setSongLoading(false));
  }, [id, isOpen]);

  useEffect(() => {
    if (tab !== 'song' || !id || !isOpen || songs.length > 0) return;
    setSongLoading(true);
    setSongError('');
    getArtistSongs(id, songPage)
      .then(({ songs: list, total }) => {
        setSongs(list);
        setSongTotal(total);
      })
      .catch((e) => setSongError(e.message || '获取失败'))
      .finally(() => setSongLoading(false));
  }, [id, songPage, tab, isOpen]);

  useEffect(() => {
    if (tab !== 'album' || !id || !isOpen) return;
    setAlbumLoading(true);
    getArtistAlbums(id)
      .then(setAlbums)
      .catch(() => {})
      .finally(() => setAlbumLoading(false));
  }, [id, tab, isOpen]);

  const totalPages = Math.ceil(songTotal / 20);

  const sortedAlbums = useMemo(() => {
    return [...albums].sort((a, b) => {
      const da = a.pub || '';
      const db = b.pub || '';
      if (!da && !db) return 0;
      if (!da) return 1;
      if (!db) return -1;
      return albumSortAsc ? da.localeCompare(db) : db.localeCompare(da);
    });
  }, [albums, albumSortAsc]);

  const handlePlaySong = useCallback((song: Song) => {
    const idx = songs.findIndex((s) => s.id === song.id);
    if (idx < 0) return;
    setQueue(songs, idx);
    setCurrentCoverUrl(song.cover);
    extractColors(song.cover).then((colors) => {
      if (colors.length >= 2) setColors({ primary: colors[0], secondary: colors[1] });
    }).catch(() => {});
    fetchPlayUrl(song.id).then((url) => {
      usePlayerStore.getState().updateSongPlayUrl(song.id, url);
    }).catch(() => {});
    fetchLyric(song.id, song.title, song.artist).then((lrc) => {
      usePlayerStore.getState().updateSongLyric(song.id, lrc);
    }).catch(() => {});
  }, [songs, setQueue, extractColors, setColors, setCurrentCoverUrl]);

  const handleDownload = useCallback((song: Song) => {
    setPickerSong(song);
    setPickerVisible(true);
  }, []);

  const handleDownloadWithQuality = useCallback((quality: Quality) => {
    const song = pickerSong;
    if (!song) return;
    setPickerVisible(false);
    setPickerSong(null);
    enqueueDownload(async () => {
      setDownloadingId(song.id);
      addTask(song, quality);
      try {
        const url = await fetchDownloadUrl(song.id, quality);
        const lyric = song.lyric || await fetchLyric(song.id, song.title, song.artist);
        const filePath = await downloadSong(
          song.id, song.title, song.artist,
          song.cover, song.album || '', url, quality,
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

  const handleAlbumPress = useCallback((album: Album) => {
    useUIStore.getState().setAlbumDetailVisible(true, {
      id: album.albumid,
      cover: album.pic || '',
      name: album.name,
      artist: album.artist,
    });
  }, []);

  const renderSongItem = useCallback(
    ({ item, index }: { item: Song; index: number }) => (
      <SongRow
        item={item}
        index={index}
        activeSongId={activeSongId}
        downloadingId={downloadingId}
        downloadTask={downloadTasks[item.id]}
        onPlay={handlePlaySong}
        onDownload={handleDownload}
      />
    ),
    [activeSongId, downloadingId, downloadTasks, handlePlaySong, handleDownload],
  );

  const renderAlbumItem = useCallback(
    ({ item }: { item: Album }) => (
      <View style={{ height: ALBUM_CARD_H, marginBottom: ALBUM_GAP }}>
        <AlbumCard album={item} onPress={handleAlbumPress} />
      </View>
    ),
    [handleAlbumPress],
  );

  const pageRange = useMemo(() => getPageRange(songPage, totalPages), [songPage, totalPages]);

  const songsContent = useMemo(() => {
    if (songLoading) return <SkeletonLoader />;
    if (songError) return (
      <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 24 }}>
        <Ionicons name="alert-circle-outline" size={36} color="rgba(255,255,255,0.2)" />
        <Text style={{ color: 'rgba(255,255,255,0.4)', fontSize: 14, marginTop: 12, textAlign: 'center' }}>{songError}</Text>
      </View>
    );
    if (songs.length === 0) return (
      <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center' }}>
        <Text style={{ color: 'rgba(255,255,255,0.3)', fontSize: 14 }}>暂无歌曲</Text>
      </View>
    );
    return (
      <Animated.View entering={FadeInDown.duration(400)} key={`songs-${songPage}`} style={{ flex: 1 }}>
        <FlatList
          key="song"
          data={songs}
          renderItem={renderSongItem}
          keyExtractor={(item) => item.id}
          getItemLayout={(_: any, i: number) => ({ length: ROW_H, offset: ROW_H * i, index: i })}
          windowSize={5}
          maxToRenderPerBatch={15}
          initialNumToRender={10}
          removeClippedSubviews
          contentContainerStyle={{ paddingBottom: 120 }}
          showsVerticalScrollIndicator={false}
          ListFooterComponent={totalPages > 1 ? (
            <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 4, paddingVertical: 16, paddingHorizontal: 12 }}>
              <TouchableOpacity
                disabled={songPage <= 1}
                onPress={() => setSongPage((p) => Math.max(1, p - 1))}
                style={[styles.pageBtn, songPage <= 1 && { opacity: 0.3 }]}
              >
                <Ionicons name="chevron-back" size={14} color="rgba(255,255,255,0.5)" />
              </TouchableOpacity>
              {pageRange.map((p, i) =>
                p === 'ellipsis' ? (
                  <Text key={`e${i}`} style={{ color: 'rgba(255,255,255,0.25)', fontSize: 12, paddingHorizontal: 2 }}>...</Text>
                ) : (
                  <TouchableOpacity
                    key={`p${p}`}
                    onPress={() => setSongPage(p as number)}
                    style={[styles.pageBtn, songPage === p && styles.pageBtnActive]}
                  >
                    <Text style={[styles.pageText, songPage === p && styles.pageTextActive]}>{p}</Text>
                  </TouchableOpacity>
                )
              )}
              <TouchableOpacity
                disabled={songPage >= totalPages}
                onPress={() => setSongPage((p) => Math.min(totalPages, p + 1))}
                style={[styles.pageBtn, songPage >= totalPages && { opacity: 0.3 }]}
              >
                <Ionicons name="chevron-forward" size={14} color="rgba(255,255,255,0.5)" />
              </TouchableOpacity>
            </View>
          ) : null}
        />
      </Animated.View>
    );
  }, [songLoading, songError, songs, renderSongItem, songPage, totalPages, pageRange]);

  const albumsContent = useMemo(() => {
    if (albumLoading) return <SkeletonLoader />;
    if (sortedAlbums.length === 0) return (
      <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center' }}>
        <Text style={{ color: 'rgba(255,255,255,0.3)', fontSize: 14 }}>暂无专辑</Text>
      </View>
    );
    return (
      <Animated.View entering={FadeInDown.duration(400)} key={`albums-${sortedAlbums.length}`} style={{ flex: 1 }}>
        <FlatList
          key="album"
          data={sortedAlbums}
          renderItem={renderAlbumItem}
          keyExtractor={(item) => item.albumid}
          numColumns={2}
          getItemLayout={(_, i) => ({ length: ALBUM_CARD_H + ALBUM_GAP, offset: (ALBUM_CARD_H + ALBUM_GAP) * Math.floor(i / 2), index: i })}
          windowSize={5}
          maxToRenderPerBatch={10}
          initialNumToRender={6}
          removeClippedSubviews
          columnWrapperStyle={{ gap: 10, paddingHorizontal: 8 }}
          contentContainerStyle={{ paddingBottom: 120, paddingTop: 4 }}
          showsVerticalScrollIndicator={false}
          ListHeaderComponent={
            <TouchableOpacity
              onPress={() => setAlbumSortAsc((p) => !p)}
              activeOpacity={0.7}
              style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'flex-end', paddingHorizontal: 8, paddingBottom: 6 }}
            >
              <Ionicons
                name={albumSortAsc ? 'arrow-up' : 'arrow-down'}
                size={13}
                color="rgba(255,255,255,0.45)"
              />
              <Text style={{ color: 'rgba(255,255,255,0.45)', fontSize: 12, marginLeft: 4 }}>
                {albumSortAsc ? '时间正序' : '时间倒序'}
              </Text>
            </TouchableOpacity>
          }
        />
      </Animated.View>
    );
  }, [albumLoading, sortedAlbums, renderAlbumItem, albumSortAsc]);

  return (
    <View style={{ position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, zIndex: 150 }} pointerEvents={isOpen ? 'auto' : 'none'}>
      {isOpen && !albumOpen && (
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
              歌手详情
            </Text>
          </View>

          <View style={{ alignItems: 'center', paddingVertical: 20 }}>
            <Image
              source={{ uri: artistPic }}
              style={{ width: 100, height: 100, borderRadius: 50, backgroundColor: 'rgba(255,255,255,0.03)' }}
            />
            <Text style={{ color: '#fff', fontSize: 20, fontWeight: '700', marginTop: 12 }}>
              {artistName}
            </Text>
            <Text style={{ color: 'rgba(255,255,255,0.35)', fontSize: 13, marginTop: 4 }}>
              歌曲 {songTotal}
            </Text>
          </View>

          <View style={{ flexDirection: 'row', paddingHorizontal: 40, paddingBottom: 10, gap: 8 }}>
            {(['song', 'album'] as const).map((t) => (
              <TouchableOpacity
                key={t}
                onPress={() => setTab(t)}
                activeOpacity={0.7}
                style={[styles.tab, { backgroundColor: tab === t ? '#818cf8' : 'rgba(255,255,255,0.04)' }]}
              >
                <Text style={[styles.tabText, { color: tab === t ? '#fff' : 'rgba(255,255,255,0.35)' }]}>
                  {t === 'song' ? '歌曲' : '专辑'}
                </Text>
              </TouchableOpacity>
            ))}
          </View>

          {tab === 'song' ? songsContent : albumsContent}

          <QualityPicker
            visible={pickerVisible}
            qualities={['flac', '320k', '128k']}
            onSelect={handleDownloadWithQuality}
            onCancel={() => { setPickerVisible(false); setPickerSong(null); }}
          />

          {downloadingId && (
            <View style={{ position: 'absolute', bottom: 100, left: 20, right: 20, alignItems: 'center' }}>
              <View style={{ backgroundColor: 'rgba(26,26,46,0.95)', borderRadius: 12, paddingVertical: 10, paddingHorizontal: 20 }}>
                <Text style={{ color: '#818cf8', fontSize: 14, fontWeight: '600' }}>
                  正在下载 {songs.find((s) => s.id === downloadingId)?.title || '...'}
                </Text>
              </View>
            </View>
          )}
        </SafeAreaView>
      </Animated.View>
    </View>
  );
});

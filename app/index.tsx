import React, { useState, useCallback, useRef, useMemo, useEffect } from 'react';
import { View, FlatList, Text, Image, TouchableOpacity, TextInput, Alert } from 'react-native';
import Animated, { FadeInDown } from 'react-native-reanimated';
import { Ionicons } from '@expo/vector-icons';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRouter } from 'expo-router';
import { useQuery } from '@tanstack/react-query';
import { usePlayerStore } from '../store/usePlayerStore';
import { useUIStore } from '../store/useUIStore';
import { useColorStore } from '../store/useColorStore';
import { useDownloadStore } from '../store/useDownloadStore';
import { useColorExtractor } from '../hooks/useColorExtractor';

import { searchMusic, searchNeteaseMusic, fetchDownloadUrlBySong, fetchLyric } from '../services/cnmusicApi';
import { searchAlbum, buildAlbumCoverUrl } from '../services/albumApi';
import { searchArtist } from '../services/artistApi';
import { downloadSong } from '../services/downloadService';
import { parseQobuzUrl } from '../services/qobuzApi';
import { SearchBar } from '../components/SearchBar';
import { ResultItem } from '../components/ResultItem';
import { AlbumCard } from '../components/AlbumCard';
import { SkeletonLoader } from '../components/SkeletonLoader';
import { DrawerController, SettingsDrawer } from '../components/SettingsDrawer';
import { QobuzTokenOverlay } from '../components/QobuzTokenOverlay';
import { AlbumDetailOverlay } from '../components/AlbumDetailOverlay';
import { SingerDetailOverlay } from '../components/SingerDetailOverlay';
import { DownloadsOverlay } from '../components/DownloadsOverlay';
import { QualityPicker } from '../components/QualityPicker';
import { Song, Quality, Album, Artist } from '../types';
import { enqueueDownload } from '../utils/downloadQueue';

type SearchMode = 'song' | 'album' | 'singer' | 'qobuz';

export default function HomeScreen() {
  const [mode, setMode] = useState<SearchMode>('song');
  const [query, setQuery] = useState('');
  const [songSubmitted, setSongSubmitted] = useState('');
  const [albumSubmitted, setAlbumSubmitted] = useState('');
  const [artistSubmitted, setArtistSubmitted] = useState('');
  const [qobuzInput, setQobuzInput] = useState('');
  const [downloadingId, setDownloadingId] = useState<string | null>(null);
  const setPicker = useUIStore((s) => s.setPicker);
  const router = useRouter();

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

  const handleSearch = useCallback(() => {
    if (mode === 'qobuz') {
      const parsed = parseQobuzUrl(qobuzInput.trim());
      if (!parsed) { Alert.alert('提示', '未识别到有效 Qobuz 链接'); return; }
      router.push(`/qobuz-detail?type=${parsed.type}&id=${parsed.id}`);
    } else {
      const q = query.trim();
      if (mode === 'song') setSongSubmitted(q);
      else if (mode === 'album') setAlbumSubmitted(q);
      else setArtistSubmitted(q);
    }
  }, [query, mode, qobuzInput, router]);

  const songQuery = useQuery({
    queryKey: ['search', songSubmitted],
    queryFn: () => searchMusic(songSubmitted),
    enabled: mode === 'song' && songSubmitted.length > 0,
  });

  const albumQuery = useQuery({
    queryKey: ['album_search', albumSubmitted],
    queryFn: () => searchAlbum(albumSubmitted, 0, 50),
    enabled: mode === 'album' && albumSubmitted.length > 0,
  });

  const artistQuery = useQuery({
    queryKey: ['artist_search', artistSubmitted],
    queryFn: () => searchArtist(artistSubmitted),
    enabled: mode === 'singer' && artistSubmitted.length > 0,
  });

  const neteaseQuery = useQuery({
    queryKey: ['netease_search', songSubmitted],
    queryFn: () => searchNeteaseMusic(songSubmitted),
    enabled: mode === 'song' && songSubmitted.length > 0,
  });

  const allSongs = (songQuery.data?.kuwo || []);
  const allNeteaseSongs = (neteaseQuery.data ?? []);
  const allAlbums = (albumQuery.data ?? []);
  const allArtists = (artistQuery.data ?? []);
  const songsRef = useRef<Song[]>(allSongs);
  songsRef.current = allSongs;
  const neteaseSongsRef = useRef<Song[]>(allNeteaseSongs);
  neteaseSongsRef.current = allNeteaseSongs;

  const handlePlaySong = useCallback(
    (song: Song, songs: Song[]) => {
      const idx = songs.findIndex((s: Song) => s.id === song.id);
      setQueue(songs, idx >= 0 ? idx : 0);
      setCurrentCoverUrl(song.cover);
      extractColors(song.cover).then((colors) => {
        if (colors.length >= 2) setColors({ primary: colors[0], secondary: colors[1] });
      }).catch(() => {});
    },
    [setQueue, extractColors, setColors, setCurrentCoverUrl],
  );

  const handleAlbumPress = useCallback((album: Album) => {
    const cover = buildAlbumCoverUrl(album);
    useUIStore.getState().setAlbumDetailVisible(true, { id: album.albumid, cover, name: album.name, artist: album.artist });
  }, []);

  const handleArtistPress = useCallback((artist: Artist) => {
    useUIStore.getState().setSingerDetailVisible(true, { id: artist.id, name: artist.name, picture: artist.picture });
  }, []);

  const handleDownload = useCallback((song: Song) => {
    setPicker(true, song);
  }, [setPicker]);

  const handleDownloadWithQuality = useCallback((quality: Quality) => {
    const song = useUIStore.getState().pickerSong;
    if (!song) return;
    useUIStore.getState().setPicker(false);
    const sId = song.id;
    console.log('[dl] enqueue', sId, quality);
    addTask(song, quality);
    setDownloadingId(sId);
    enqueueDownload(async () => {
      try {
        console.log('[dl] starting download for', sId);
        const url = await fetchDownloadUrlBySong(song, quality);
        console.log('[dl] got url:', url?.substring(0, 60));
        const lyric = song.lyric || await fetchLyric(sId, song.title, song.artist);
        console.log('[dl] lyric length:', lyric?.length || 0);
        const filePath = await downloadSong(
          sId, song.title, song.artist,
          song.cover, song.album, url, quality,
          (p) => updateProgress(sId, p * 100),
          lyric,
        );
        console.log('[dl] complete:', filePath);
        completeTask(sId, filePath);
        addDownloadedId(sId);
        addToQueue(song);
        if (typeof Notification !== 'undefined' && Notification.permission === 'granted') {
          new Notification('下载完成', { body: `${song.title} - ${song.artist}` });
        }
      } catch (e) {
        console.log('[dl] error:', e);
        failTask(sId);
      }
      setDownloadingId(null);
    });
  }, [addTask, updateProgress, completeTask, addDownloadedId, addToQueue, failTask]);

  const activeSongId = usePlayerStore((s) => s.currentSong?.id);

  const renderSongItem = useCallback(
    ({ item }: { item: Song }) => {
      const songs = item.platform === 'netease' ? neteaseSongsRef.current : songsRef.current;
      return (
        <ResultItem
          song={item}
          activeSongId={activeSongId}
          onPress={() => handlePlaySong(item, songs)}
          onDownload={handleDownload}
        />
      );
    },
    [handlePlaySong, handleDownload, songsRef, neteaseSongsRef, activeSongId],
  );

  const renderAlbumItem = useCallback(
    ({ item }: { item: Album }) => (
      <AlbumCard album={item} onPress={handleAlbumPress} />
    ),
    [handleAlbumPress],
  );

  const renderArtistItem = useCallback(
    ({ item }: { item: Artist }) => (
      <TouchableOpacity
        onPress={() => handleArtistPress(item)}
        activeOpacity={0.7}
        style={{
          flexDirection: 'row', alignItems: 'center',
          paddingVertical: 10, paddingHorizontal: 12,
        }}
      >
        <Image
          source={{ uri: item.picture }}
          style={{ width: 56, height: 56, borderRadius: 28, backgroundColor: 'rgba(255,255,255,0.03)' }}
        />
        <View style={{ marginLeft: 14, flex: 1 }}>
          <Text style={{ color: '#fff', fontSize: 15, fontWeight: '600' }} numberOfLines={1}>
            {item.name}
          </Text>
          <Text style={{ color: 'rgba(255,255,255,0.35)', fontSize: 12, marginTop: 2 }}>
            {item.musicNum} 首歌曲
          </Text>
        </View>
      </TouchableOpacity>
    ),
    [handleArtistPress],
  );

  const searchContent = useMemo(() => (
    mode === 'qobuz' ? (
      <View style={{ flex: 1, alignItems: 'center', paddingHorizontal: 32, paddingTop: 60 }}>
        <Text style={{ color: 'rgba(255,255,255,0.6)', fontSize: 24, fontWeight: '700', marginBottom: 24 }}>
          Qobuz解析
        </Text>
        <View style={{
          flexDirection: 'row', alignItems: 'center',
          backgroundColor: 'rgba(255,255,255,0.06)', borderRadius: 14,
          width: '100%', height: 52,
        }}>
          <TextInput
            value={qobuzInput}
            onChangeText={setQobuzInput}
            placeholder="请粘贴 Qobuz 单曲链接或者专辑链接"
            placeholderTextColor="rgba(255,255,255,0.3)"
            autoCapitalize="none"
            autoCorrect={false}
            onSubmitEditing={handleSearch}
            returnKeyType="go"
            style={{
              flex: 1, color: '#fff', fontSize: 15,
              paddingHorizontal: 16, height: '100%',
            }}
          />
          {qobuzInput.length > 0 && (
            <TouchableOpacity onPress={() => setQobuzInput('')} activeOpacity={0.7} style={{ paddingHorizontal: 6, marginLeft: -10 }}>
              <Ionicons name="close-circle" size={18} color="rgba(255,255,255,0.2)" />
            </TouchableOpacity>
          )}
          <TouchableOpacity
            onPress={handleSearch}
            activeOpacity={0.7}
            style={{
              width: 48, height: 48, borderRadius: 12,
              backgroundColor: '#818cf8', alignItems: 'center', justifyContent: 'center',
              marginRight: 2,
            }}
          >
            <Ionicons name="arrow-forward" size={20} color="#fff" />
          </TouchableOpacity>
        </View>
        <Text style={{ color: 'rgba(255,255,255,0.25)', fontSize: 12, textAlign: 'center', marginTop: 12 }}>
          请在设置页面配置 Token 再使用
        </Text>
      </View>
    ) : mode === 'song' ? (
      <View style={{ flex: 1 }}>
        {(songQuery.isLoading || neteaseQuery.isLoading) ? (
          <SkeletonLoader />
        ) : (allSongs.length > 0 || allNeteaseSongs.length > 0) ? (
          <Animated.View entering={FadeInDown.duration(400)} key={`song-${songSubmitted}`} style={{ flex: 1, flexDirection: 'row', paddingHorizontal: 8 }}>
            <View style={{ flex: 1 }}>
              {allSongs.length > 0 ? (
                <FlatList
                  data={allSongs}
                  renderItem={renderSongItem}
                  keyExtractor={(item) => item.id}
                  windowSize={5}
                  maxToRenderPerBatch={15}
                  initialNumToRender={10}
                  removeClippedSubviews
                  contentContainerStyle={{ paddingBottom: 120 }}
                  showsVerticalScrollIndicator={false}
                />
              ) : (
                <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center' }}>
                  <Text style={{ color: 'rgba(255,255,255,0.3)', fontSize: 14 }}>未找到结果</Text>
                </View>
              )}
            </View>
            <View style={{ width: 1, backgroundColor: 'rgba(255,255,255,0.04)', marginVertical: 8 }} />
            <View style={{ flex: 1 }}>
              {neteaseQuery.isLoading ? (
                <SkeletonLoader />
              ) : allNeteaseSongs.length > 0 ? (
                <FlatList
                  data={allNeteaseSongs}
                  renderItem={renderSongItem}
                  keyExtractor={(item) => item.id}
                  windowSize={5}
                  maxToRenderPerBatch={15}
                  initialNumToRender={10}
                  removeClippedSubviews
                  contentContainerStyle={{ paddingBottom: 120 }}
                  showsVerticalScrollIndicator={false}
                />
              ) : allNeteaseSongs.length === 0 && !neteaseQuery.isLoading && songSubmitted.length > 0 ? (
                <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center' }}>
                  <Text style={{ color: 'rgba(255,255,255,0.3)', fontSize: 14 }}>未找到结果</Text>
                </View>
              ) : null}
            </View>
          </Animated.View>
        ) : songSubmitted.length > 0 ? (
          <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center' }}>
            <Text style={{ color: 'rgba(255,255,255,0.3)', fontSize: 14 }}>未找到结果</Text>
          </View>
        ) : null}
      </View>
    ) : mode === 'singer' ? (
      <View style={{ flex: 1 }}>
        {artistQuery.isLoading ? (
          <SkeletonLoader />
        ) : allArtists.length > 0 ? (
          <Animated.View entering={FadeInDown.duration(400)} key={`artist-${artistSubmitted}`} style={{ flex: 1 }}>
            <FlatList
              data={allArtists}
              renderItem={renderArtistItem}
              keyExtractor={(item) => item.id}
              windowSize={5}
              maxToRenderPerBatch={15}
              initialNumToRender={10}
              removeClippedSubviews
              contentContainerStyle={{ paddingBottom: 120, paddingTop: 4 }}
              showsVerticalScrollIndicator={false}
            />
          </Animated.View>
        ) : artistSubmitted.length > 0 ? (
          <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center' }}>
            <Text style={{ color: 'rgba(255,255,255,0.3)', fontSize: 14 }}>未找到结果</Text>
          </View>
        ) : null}
      </View>
    ) : (
      <View style={{ flex: 1 }}>
        {albumQuery.isLoading ? (
          <SkeletonLoader />
        ) : allAlbums.length > 0 ? (
          <Animated.View entering={FadeInDown.duration(400)} key={`album-${albumSubmitted}`} style={{ flex: 1 }}>
            <FlatList
              data={allAlbums}
              renderItem={renderAlbumItem}
              keyExtractor={(item) => item.albumid}
              numColumns={2}
              windowSize={5}
              maxToRenderPerBatch={10}
              initialNumToRender={6}
              removeClippedSubviews
              columnWrapperStyle={{ gap: 10, paddingHorizontal: 8 }}
              contentContainerStyle={{ gap: 10, paddingBottom: 120, paddingTop: 4 }}
              showsVerticalScrollIndicator={false}
            />
          </Animated.View>
        ) : albumSubmitted.length > 0 ? (
          <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center' }}>
            <Text style={{ color: 'rgba(255,255,255,0.3)', fontSize: 14 }}>未找到结果</Text>
          </View>
        ) : null}
      </View>
    )
  ), [mode, songQuery.isLoading, neteaseQuery.isLoading, albumQuery.isLoading, artistQuery.isLoading, allSongs, allNeteaseSongs, allAlbums, allArtists, renderSongItem, renderAlbumItem, renderArtistItem, songSubmitted, albumSubmitted, artistSubmitted, handleSearch, qobuzInput]);

  return (
    <SafeAreaView style={{ flex: 1, backgroundColor: '#0a0a1a' }} edges={['top']}>
      <View style={{ flexDirection: 'row', alignItems: 'center', paddingHorizontal: 16, paddingTop: 40, paddingBottom: 20 }}>
        <DrawerController />
        <Text style={{ color: '#fff', fontSize: 26, fontWeight: '700', letterSpacing: -0.5, flex: 1 }}>
          安妮音乐盒
        </Text>
        <Image
          source={require('../assets/annie.jpg')}
          style={{ width: 40, height: 40, borderRadius: 10 }}
        />
      </View>

      <View style={{ flexDirection: 'row', paddingHorizontal: 16, paddingBottom: 8, gap: 6 }}>
        {(['song', 'album', 'singer', 'qobuz'] as const).map((m) => (
          <TouchableOpacity
            key={m}
            onPress={() => { setMode(m); }}
            activeOpacity={0.7}
            style={{
              flex: 1, height: 36, borderRadius: 10,
              alignItems: 'center', justifyContent: 'center',
              backgroundColor: mode === m ? '#818cf8' : 'rgba(255,255,255,0.05)',
            }}
          >
            <Text style={{ color: mode === m ? '#fff' : 'rgba(255,255,255,0.4)', fontSize: 13, fontWeight: '600' }}>
              {m === 'song' ? '单曲搜索' : m === 'album' ? '专辑搜索' : m === 'singer' ? '歌手搜索' : '国外解析'}
            </Text>
          </TouchableOpacity>
        ))}
      </View>

      {mode !== 'qobuz' && <SearchBar query={query} onQueryChange={setQuery} onSubmit={handleSearch} />}

      {searchContent}

      <SettingsDrawer />
      <QobuzTokenOverlay />
      <SingerDetailOverlay />
      <AlbumDetailOverlay />
      <DownloadsOverlay />

      <QualityPicker onSelect={handleDownloadWithQuality} />

      {downloadingId && (
        <View style={{ position: 'absolute', bottom: 100, left: 20, right: 20, alignItems: 'center' }}>
          <View style={{ backgroundColor: 'rgba(26,26,46,0.95)', borderRadius: 12, paddingVertical: 10, paddingHorizontal: 20 }}>
            <Text style={{ color: '#818cf8', fontSize: 14, fontWeight: '600' }}>
              正在下载 {[...allSongs, ...allNeteaseSongs].find((s: Song) => s.id === downloadingId)?.title || '...'}
            </Text>
          </View>
        </View>
      )}


    </SafeAreaView>
  );
}

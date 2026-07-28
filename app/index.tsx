import React, { useState, useCallback, useRef, useEffect } from 'react';
import { View, Text, Image, TouchableOpacity, Alert, BackHandler } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRouter } from 'expo-router';
import { useQuery } from '@tanstack/react-query';
import { usePlayerStore } from '../store/usePlayerStore';
import { useUIStore } from '../store/useUIStore';
import { useColorStore } from '../store/useColorStore';
import { useDownloadStore } from '../store/useDownloadStore';
import { useColorExtractor } from '../hooks/useColorExtractor';

import { fetchDownloadUrlBySong, fetchLyric } from '../services/musicApi';
import { searchNeteaseMusic } from '../services/platforms/neteaseApi';
import { searchMusic, searchArtist, searchAlbum, buildAlbumCoverUrl, getAlbumSongs, enrichKuwoSong } from '../services/platforms/kuwoApi';
import { enrichQQSong } from '../services/platforms/qqApi';
import { enrichKuGouSong } from '../services/platforms/kugouApi';
import { fetchAndCacheCover } from '../services/assetProxy';
import { downloadSong } from '../services/downloadService';
import { parseQobuzUrl } from '../services/platforms/qobuzApi';
import { parseKkboxUrl } from '../services/platforms/kkboxApi';
import { SearchBar } from '../components/SearchBar';
import { PlaylistSearchView } from '../components/PlaylistSearchView';
import { ModeDropdown } from '../components/ModeDropdown';
import { DrawerController, MenuDrawer } from '../components/MenuDrawer';
import { QobuzTokenOverlay } from '../components/QobuzTokenOverlay';
import { KkboxAccountOverlay } from '../components/KkboxAccountOverlay';
import { AlbumDetailOverlay } from '../components/AlbumDetailOverlay';
import { SingerDetailOverlay } from '../components/SingerDetailOverlay';
import { DownloadsOverlay } from '../components/DownloadsOverlay';
import { QualityPicker } from '../components/QualityPicker';
import { SongSearchResults } from '../components/SongSearchResults';
import { AlbumSearchResults } from '../components/AlbumSearchResults';
import { SingerSearchResults } from '../components/SingerSearchResults';
import { ForeignSearchView } from '../components/ForeignSearchView';
import { PlaylistDetailOverlay } from '../components/PlaylistDetailOverlay';
import { Song, Quality, Album, Artist } from '../types';
import { enqueueDownload } from '../utils/downloadQueue';
import { useSearchSelectionStore } from '../store/useSearchSelectionStore';

type SearchMode = 'song' | 'album' | 'singer' | 'qobuz' | 'playlist';

export default function HomeScreen() {
  const [mode, setMode] = useState<SearchMode>('song');
  const [modePickerVisible, setModePickerVisible] = useState(false);
  const [query, setQuery] = useState('');
  const [songSubmitted, setSongSubmitted] = useState('');
  const [albumSubmitted, setAlbumSubmitted] = useState('');
  const [artistSubmitted, setArtistSubmitted] = useState('');
  const [downloadingId, setDownloadingId] = useState<string | null>(null);
  const [batchPickerVisible, setBatchPickerVisible] = useState(false);
  const [batchDownloading, setBatchDownloading] = useState(false);
  const downloadTimer = useRef<ReturnType<typeof setTimeout>>(undefined);
  const setDownloadingIdAuto = useCallback((id: string | null) => {
    if (downloadTimer.current) clearTimeout(downloadTimer.current);
    setDownloadingId(id);
    if (id) downloadTimer.current = setTimeout(() => setDownloadingId(null), 2000);
  }, []);
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

  const handleModeSelect = useCallback((m: SearchMode) => {
    setMode(m);
    setModePickerVisible(false);
  }, []);

  useEffect(() => {
    if (!modePickerVisible) return;
    const onBack = () => { setModePickerVisible(false); return true; };
    const sub = BackHandler.addEventListener('hardwareBackPress', onBack);
    return () => sub.remove();
  }, [modePickerVisible]);

  const handleSearch = useCallback(() => {
    const q = query.trim();
    if (mode === 'song') setSongSubmitted(q);
    else if (mode === 'album') setAlbumSubmitted(q);
    else setArtistSubmitted(q);
  }, [query, mode]);

  const handleQobuzSubmit = useCallback((text: string) => {
    const parsed = parseQobuzUrl(text);
    if (!parsed) { Alert.alert('提示', '未识别到有效 Qobuz 链接'); return; }
    router.push(`/qobuz-detail?type=${parsed.type}&id=${parsed.id}`);
  }, [router]);

  const handleKkboxSubmit = useCallback((text: string) => {
    const parsed = parseKkboxUrl(text);
    if (!parsed) { Alert.alert('提示', '未识别到有效 KKBOX 链接'); return; }
    router.push(`/kkbox-detail?type=${parsed.type}&id=${parsed.id}`);
  }, [router]);

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

  const allSongs = (songQuery.data ?? []);
  const allNeteaseSongs = (neteaseQuery.data ?? []);
  const allAlbums = (albumQuery.data ?? []);
  const allArtists = (artistQuery.data ?? []);

  const handlePlaySong = useCallback(
    async (song: Song, songs: Song[]) => {
      const cachedUri = await fetchAndCacheCover(song.cover);
      setCurrentCoverUrl(cachedUri);
      const idx = songs.findIndex((s: Song) => s.id === song.id);
      setQueue(songs, idx >= 0 ? idx : 0);
      extractColors(cachedUri).then((colors) => {
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

  const handleLongPress = useCallback((song: Song) => {
    const store = useSearchSelectionStore.getState();
    if (store.selectedSongs.some((s) => s.id === song.id)) {
      store.removeSong(song.id);
    } else {
      store.addSong(song);
    }
  }, []);

  const handleClearSelection = useCallback(() => {
    useSearchSelectionStore.getState().clearAll();
  }, []);

  const handleBatchDownload = useCallback((quality: Quality) => {
    setBatchPickerVisible(false);
    setBatchDownloading(true);
    const songs = useSearchSelectionStore.getState().selectedSongs;
    for (const s of songs) addTask(s, quality);
    const batchId = 'search_batch_' + Date.now();
    enqueueDownload(batchId, async () => {
      let success = 0;
      for (let i = 0; i < songs.length; i++) {
        const song = songs[i];
        updateProgress(song.id, 0);
        try {
          const url = await fetchDownloadUrlBySong(song, quality);
          const { lrc: lyric, tlyric } = await fetchLyric(song.id, song.title, song.artist);
          const extra = song.platform === 'kuwo' ? await enrichKuwoSong(song.id, song.albumId || '') : song.platform === 'kugou' ? await enrichKuGouSong(song.id) : song.platform === 'qq' ? await enrichQQSong(song.id) : {};
          const filePath = await downloadSong(
            song.id, song.title, song.artist,
            song.cover, song.album || '', url, quality,
            (p) => updateProgress(song.id, p * 100),
            lyric, tlyric ?? undefined,
            song.albumArtist, song.trackNumber || extra.trackNumber, song.discNumber, song.releaseDate || extra.releaseDate,
          );
          completeTask(song.id, filePath);
          addDownloadedId(song.id);
          addToQueue(song);
          success++;
        } catch {
          failTask(song.id);
        }
      }
      setBatchDownloading(false);
      useSearchSelectionStore.getState().clearAll();
    });
  }, [addTask, updateProgress, completeTask, addDownloadedId, addToQueue, failTask]);

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
    setDownloadingIdAuto(sId);
    enqueueDownload(sId, async () => {
      try {
        console.log('[dl] starting download for', sId);
        const url = await fetchDownloadUrlBySong(song, quality);
        console.log('[dl] got url:', url?.substring(0, 60));
        const { lrc: lyric, tlyric } = song.lyric && song.tlyric
          ? { lrc: song.lyric, tlyric: song.tlyric }
          : await fetchLyric(sId, song.title, song.artist);
        console.log('[dl] lyric length:', lyric?.length || 0);
        const extra = song.platform === 'kuwo' ? await enrichKuwoSong(song.id, song.albumId || '') : song.platform === 'kugou' ? await enrichKuGouSong(song.id) : song.platform === 'qq' ? await enrichQQSong(song.id) : {};
        const filePath = await downloadSong(
          sId, song.title, song.artist,
          song.cover, song.album, url, quality,
          (p) => updateProgress(sId, p * 100),
          lyric, tlyric ?? undefined,
          song.albumArtist, song.trackNumber || extra.trackNumber, song.discNumber, song.releaseDate || extra.releaseDate,
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
    });
  }, [addTask, updateProgress, completeTask, addDownloadedId, addToQueue, failTask]);

  const selectedSongs = useSearchSelectionStore((s) => s.selectedSongs);

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

      <View style={{ paddingHorizontal: 16, paddingBottom: 8 }}>
        <ModeDropdown
          mode={mode}
          open={modePickerVisible}
          onToggle={() => setModePickerVisible((v) => !v)}
          onSelect={handleModeSelect}
        />
      </View>

      {mode === 'song' && selectedSongs.length > 0 && (
        <View style={{
          flexDirection: 'row', alignItems: 'center', gap: 8,
          backgroundColor: 'rgba(129,140,248,0.08)', borderRadius: 14,
          paddingVertical: 10, paddingHorizontal: 14, marginHorizontal: 16, marginBottom: 8,
        }}>
            <TouchableOpacity
              onPress={() => useSearchSelectionStore.getState().setOverlayVisible(true)}
              activeOpacity={0.7}
            style={{ flexDirection: 'row', alignItems: 'center', gap: 6, flex: 1 }}
          >
            <View style={{
              width: 22, height: 22, borderRadius: 11,
              backgroundColor: '#818cf8', alignItems: 'center', justifyContent: 'center',
            }}>
              <Text style={{ color: '#fff', fontSize: 11, fontWeight: '700' }}>{selectedSongs.length}</Text>
            </View>
            <Text style={{ color: 'rgba(255,255,255,0.7)', fontSize: 13, fontWeight: '500' }}>待下载歌曲</Text>
          </TouchableOpacity>
          <TouchableOpacity onPress={handleClearSelection} activeOpacity={0.7} style={{ padding: 4 }}>
            <Text style={{ color: 'rgba(255,255,255,0.35)', fontSize: 12 }}>清空</Text>
          </TouchableOpacity>
          <TouchableOpacity
            onPress={() => setBatchPickerVisible(true)}
            disabled={batchDownloading}
            activeOpacity={0.7}
            style={{
              backgroundColor: '#818cf8', borderRadius: 10,
              paddingVertical: 8, paddingHorizontal: 16,
              opacity: batchDownloading ? 0.5 : 1,
            }}
          >
            {batchDownloading ? (
              <Text style={{ color: '#fff', fontSize: 12, fontWeight: '600' }}>下载中...</Text>
            ) : (
              <Text style={{ color: '#fff', fontSize: 12, fontWeight: '600' }}>下载全部</Text>
            )}
          </TouchableOpacity>
        </View>
      )}

      {mode !== 'qobuz' && mode !== 'playlist' && <SearchBar query={query} onQueryChange={setQuery} onSubmit={handleSearch} />}

      {mode === 'playlist' ? (
        <PlaylistSearchView />
      ) : mode === 'qobuz' ? (
        <ForeignSearchView onQobuzSubmit={handleQobuzSubmit} onKkboxSubmit={handleKkboxSubmit} />
      ) : mode === 'song' ? (
        <SongSearchResults
          kuwoSongs={allSongs}
          neteaseSongs={allNeteaseSongs}
          kuwoLoading={songQuery.isLoading}
          neteaseLoading={neteaseQuery.isLoading}
          query={songSubmitted}
          onPlay={handlePlaySong}
          onDownload={handleDownload}
          onToggleSelect={handleLongPress}
        />
      ) : mode === 'singer' ? (
        <SingerSearchResults
          artists={allArtists}
          loading={artistQuery.isLoading}
          query={artistSubmitted}
          onArtistPress={handleArtistPress}
        />
      ) : (
        <AlbumSearchResults
          albums={allAlbums}
          loading={albumQuery.isLoading}
          query={albumSubmitted}
          onAlbumPress={handleAlbumPress}
        />
      )}


      <MenuDrawer />
      <QobuzTokenOverlay />
      <KkboxAccountOverlay />
      <SingerDetailOverlay />
      <AlbumDetailOverlay />
      <PlaylistDetailOverlay />
      <DownloadsOverlay />

      <QualityPicker onSelect={handleDownloadWithQuality} />

      <QualityPicker
        visible={batchPickerVisible}
        qualities={['flac', '320k', '128k']}
        onSelect={handleBatchDownload}
        onCancel={() => setBatchPickerVisible(false)}
      />

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
// test

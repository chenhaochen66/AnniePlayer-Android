import React, { useState, useCallback, useEffect, useRef, useMemo } from 'react';
import { View, Text, FlatList, Image, TouchableOpacity, ActivityIndicator, StyleSheet, BackHandler } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { Ionicons } from '@expo/vector-icons';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { usePlayerStore } from '../store/usePlayerStore';
import { useDownloadStore } from '../store/useDownloadStore';
import { useColorStore } from '../store/useColorStore';
import { useColorExtractor } from '../hooks/useColorExtractor';
import { useKkboxStore } from '../store/useKkboxStore';
import { getSongs, getAlbum, getDownloadUrl, getPlayUrl, getSongLyrics, mapSongToKkboxTrack, mapAlbumToKkboxAlbum, authDevice, renewSession } from '../services/kkboxApi';
import { downloadSong } from '../services/downloadService';
import { scanFile, tagAudioFile } from '../modules/media-scanner/src/MediaScanner';
import { streamDecryptKkboxAudio, formatNameToQuality, isDrmFormat, qualityExtension } from '../services/kkboxApi';
import { enqueueDownload } from '../utils/downloadQueue';
import * as FileSystem from 'expo-file-system/legacy';
import { screenPadding } from '../constants/tokens';
import type { KkboxTrack, KkboxSession } from '../types';

const ROW_H = 60;

const TrackRow = React.memo(({ item, index, selectMode, isSelected, downloadingId, onPlay, onDownload, onToggle }: {
  item: KkboxTrack; index: number; selectMode: boolean; isSelected: boolean;
  downloadingId: string | null;
  onPlay: (t: KkboxTrack) => void; onDownload: (t: KkboxTrack) => void; onToggle: (id: string) => void;
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

export default function KkboxDetailPage() {
  const { type, id } = useLocalSearchParams<{ type: string; id: string }>();
  const router = useRouter();
  const closing = useRef(false);
  const [tracks, setTracks] = useState<KkboxTrack[]>([]);
  const [cover, setCover] = useState('');
  const [title, setTitle] = useState('');
  const [artist, setArtist] = useState('');
  const [year, setYear] = useState<string | undefined>();
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
  const activeAccount = useKkboxStore((s) => s.accounts.find((a) => a.isActive));
  const getActive = useKkboxStore((s) => s.getActiveAccount);
  const { extractColors } = useColorExtractor();

  const handleBack = useCallback(() => {
    if (closing.current) return;
    closing.current = true;
    setTimeout(() => router.back(), 200);
  }, [router]);

  useEffect(() => {
    if (!type || !id || !activeAccount?.session?.loggedIn) {
      setError(activeAccount?.session?.loggedIn ? '缺少参数' : '请先在设置中配置并登录 KKBOX');
      setLoading(false);
      return;
    }
    setLoading(true);
    setError('');
    (async () => {
      try {
        if (type === 'song') {
          const rawSongs = await getSongs(activeAccount!.session!.sid, [id]);
          if (rawSongs.length === 0) throw new Error('歌曲不存在');
          const t = mapSongToKkboxTrack(rawSongs[0]);
          setTracks([t]);
          setCover(t.cover);
          setTitle(t.title);
          setArtist(t.artist);
        } else {
          const { album, songs } = await getAlbum(activeAccount!.session!.sid, id);
          const a = mapAlbumToKkboxAlbum(album, songs);
          setTracks(a.tracks);
          setCover(a.cover);
          setTitle(a.title);
          setArtist(a.artist || a.tracks[0]?.artist || '');
          setYear(a.year);
        }
      } catch (e: any) {
        if (e.message?.includes('session') || e.message?.includes('login')) {
          setError('会话已过期，请在设置中重新登录');
        } else {
          setError(e.message || '获取失败');
        }
      } finally {
        setLoading(false);
      }
    })();
    }, [type, id, activeAccount]);

  const ensureSession = useCallback(async (): Promise<{ session: KkboxSession; accountId: string }> => {
    const acct = useKkboxStore.getState().getActiveAccount();
    if (!acct?.session?.loggedIn) throw new Error('未登录');
    return { session: acct.session, accountId: acct.id };
  }, []);

  const handlePlay = useCallback(async (track: KkboxTrack) => {
    try {
      const { session: s, accountId } = await ensureSession();
      const { url, sid: newSid } = await getPlayUrl(s.sid, track.id, s.kkid);
      if (!url) return;
      if (newSid !== s.sid) useKkboxStore.getState().updateAccountSession(accountId, { ...s, sid: newSid });
      const allTracks = tracks.length > 1 ? tracks : [track];
      const idx = allTracks.findIndex((t) => t.id === track.id);
      setCurrentCoverUrl(track.cover);
      extractColors(track.cover).then((colors) => {
        if (colors.length >= 2) setColors({ primary: colors[0], secondary: colors[1] });
      }).catch(() => {});
      const songs: any[] = allTracks.map((t) => ({
        id: `kkbox:${t.id}`, title: t.title, artist: t.artist,
        cover: t.cover, album: t.album, duration: t.duration,
        albumArtist: t.albumArtist, trackNumber: String(t.trackNumber || ''),
        platform: 'kkbox' as const, playUrl: url,
      }));
      setQueue(songs, idx >= 0 ? idx : 0);
    } catch (e: any) {
      setError(e.message);
    }
  }, [tracks, ensureSession, extractColors, setColors, setCurrentCoverUrl, setQueue]);

  const handleDownloadTrack = useCallback((track: KkboxTrack) => {
    (async () => {
      try {
        const { session: s, accountId } = await ensureSession();
        setDownloadingId(track.id);
        const sId = `kkbox:${track.id}`;
        const song: any = {
          id: sId, title: track.title, artist: track.artist,
          cover: track.cover, album: track.album,
          platform: 'kkbox' as const,
        };
        addTask(song, 'hires');
        enqueueDownload(sId, async () => {
          try {
            const { url, formatName, sid: newSid } = await getDownloadUrl(s.sid, track.id, s.kkid, 'hires');
            if (newSid !== s.sid) useKkboxStore.getState().updateAccountSession(accountId, { ...s, sid: newSid });
            const quality = formatNameToQuality(formatName);
            const { lrc: lyric, tlyric } = await getSongLyrics(s.sid, track.id);
            let filePath;
            if (isDrmFormat(formatName)) {
              const tmp = FileSystem.cacheDirectory + 'kkbox_drm_' + sId.replace(/[^a-zA-Z0-9_-]/g, '_') + qualityExtension(quality);
              await streamDecryptKkboxAudio(url, s.licContentKey, tmp, (p) => updateProgress(sId, p * 90));
              await new Promise((r) => setImmediate(r));
              let coverBase64 = '';
              updateProgress(sId, 91);
              await new Promise((r) => setImmediate(r));
              try {
                const ext = (track.cover.split('.').pop()?.split('?')[0] || 'jpg');
                const coverTmp = FileSystem.cacheDirectory + 'cvr_' + sId.replace(/[^a-zA-Z0-9_-]/g, '_') + '.' + ext;
                const cr = await FileSystem.downloadAsync(track.cover, coverTmp);
                coverBase64 = await FileSystem.readAsStringAsync(cr.uri, { encoding: FileSystem.EncodingType.Base64 });
                await FileSystem.deleteAsync(coverTmp, { idempotent: true }).catch(() => {});
              } catch {}
              updateProgress(sId, 93);
              await new Promise((r) => setImmediate(r));
              const mergedLyric = (tlyric ? lyric + '\n' + tlyric : lyric) || '';
              await tagAudioFile(tmp.replace(/^file:\/\//, ''), track.title, track.artist, track.album || '', mergedLyric, coverBase64, track.cover, track.albumArtist, String(track.trackNumber || ''), '1', track.releaseDate || '');
              updateProgress(sId, 96);
              await new Promise((r) => setImmediate(r));
              const safeName = (track.title + ' - ' + track.artist + qualityExtension(quality)).replace(/[/\\?%*:|"<>]/g, '_');
              const finalPath = FileSystem.cacheDirectory + 'downloads/' + safeName;
              await FileSystem.makeDirectoryAsync(FileSystem.cacheDirectory + 'downloads/', { intermediates: true }).catch(() => {});
              await FileSystem.moveAsync({ from: tmp, to: finalPath }).catch(() => FileSystem.copyAsync({ from: tmp, to: finalPath }));
              updateProgress(sId, 98);
              await new Promise((r) => setImmediate(r));
              let uri = '';
              try { uri = await scanFile(finalPath.replace(/^file:\/\//, '')) || ''; } catch {}
              if (uri) await FileSystem.deleteAsync(finalPath, { idempotent: true }).catch(() => {});
              filePath = uri || finalPath;
            } else {
              filePath = await downloadSong(
                sId, track.title, track.artist,
                track.cover, track.album, url, quality as any,
                (p) => updateProgress(sId, p * 100), lyric, tlyric ?? undefined,
              );
            }
            completeTask(sId, filePath);
            addDownloadedId(sId);
            addToQueue(song);
          } catch (e: any) {
            console.error('kkbox-detail download error:', e?.message || e);
            failTask(sId);
          }
          setDownloadingId(null);
        });
      } catch (e: any) {
        setDownloadingId(null);
      }
    })();
  }, [ensureSession, addTask, updateProgress, completeTask, addDownloadedId, addToQueue, failTask]);

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

  useEffect(() => {
    if (!selectMode) return;
    const onBack = () => { setSelectMode(false); return true; };
    const sub = BackHandler.addEventListener('hardwareBackPress', onBack);
    return () => sub.remove();
  }, [selectMode]);

  const handleSelectAll = useCallback(() => {
    setSelectedIds((prev) => {
      if (prev.size === tracks.length) return new Set();
      return new Set(tracks.map((t) => t.id));
    });
  }, [tracks]);

  const handleBatchDownload = useCallback(() => {
    (async () => {
      try {
        const { session: s, accountId } = await ensureSession();
        setBatchDownloading(true);
        const selected = tracks.filter((t) => selectedIds.has(t.id));
        for (const t of selected) {
          const song: any = {
            id: `kkbox:${t.id}`, title: t.title, artist: t.artist,
            cover: t.cover, album: t.album, platform: 'kkbox' as const,
          };
          addTask(song, 'hires');
        }
        enqueueDownload('batch_' + Date.now(), async () => {
          let success = 0;
          for (let i = 0; i < selected.length; i++) {
            const t = selected[i];
            const sId = `kkbox:${t.id}`;
            setBatchProgress(`${i + 1}/${selected.length} ${t.title}`);
            updateProgress(sId, 0);
            try {
              const { url, formatName, sid: newSid } = await getDownloadUrl(s.sid, t.id, s.kkid, 'hires');
              if (newSid !== s.sid) useKkboxStore.getState().updateAccountSession(accountId, { ...s, sid: newSid });
              const quality = formatNameToQuality(formatName);
              const { lrc: lyric, tlyric } = await getSongLyrics(s.sid, t.id);
              let filePath;
              if (isDrmFormat(formatName)) {
                const tmp = FileSystem.cacheDirectory + 'kkbox_drm_' + sId.replace(/[^a-zA-Z0-9_-]/g, '_') + qualityExtension(quality);
                await streamDecryptKkboxAudio(url, s.licContentKey, tmp, (p) => updateProgress(sId, p * 90));
                await new Promise((r) => setImmediate(r));
                let coverBase64 = '';
                updateProgress(sId, 91);
                await new Promise((r) => setImmediate(r));
                try {
                  const ext = (t.cover.split('.').pop()?.split('?')[0] || 'jpg');
                  const coverTmp = FileSystem.cacheDirectory + 'cvr_' + sId.replace(/[^a-zA-Z0-9_-]/g, '_') + '.' + ext;
                  const cr = await FileSystem.downloadAsync(t.cover, coverTmp);
                  coverBase64 = await FileSystem.readAsStringAsync(cr.uri, { encoding: FileSystem.EncodingType.Base64 });
                  await FileSystem.deleteAsync(coverTmp, { idempotent: true }).catch(() => {});
                } catch {}
                updateProgress(sId, 93);
                await new Promise((r) => setImmediate(r));
                const mergedLyric = (lyric && tlyric ? lyric + '\n' + tlyric : lyric) || '';
                await tagAudioFile(tmp.replace(/^file:\/\//, ''), t.title, t.artist, t.album || '', mergedLyric, coverBase64, t.cover, t.albumArtist, String(t.trackNumber || ''), '1', t.releaseDate || '');
                updateProgress(sId, 96);
                await new Promise((r) => setImmediate(r));
                const safeName = (t.title + ' - ' + t.artist + qualityExtension(quality)).replace(/[/\\?%*:|"<>]/g, '_');
                const finalPath = FileSystem.cacheDirectory + 'downloads/' + safeName;
                await FileSystem.makeDirectoryAsync(FileSystem.cacheDirectory + 'downloads/', { intermediates: true }).catch(() => {});
                await FileSystem.moveAsync({ from: tmp, to: finalPath }).catch(() => FileSystem.copyAsync({ from: tmp, to: finalPath }));
                updateProgress(sId, 98);
                await new Promise((r) => setImmediate(r));
                let uri = '';
                try { uri = await scanFile(finalPath.replace(/^file:\/\//, '')) || ''; } catch {}
                if (uri) await FileSystem.deleteAsync(finalPath, { idempotent: true }).catch(() => {});
                filePath = uri || finalPath;
              } else {
                filePath = await downloadSong(
                  sId, t.title, t.artist,
                  t.cover, t.album, url, quality as any,
                  (p) => updateProgress(sId, p * 100), lyric, tlyric ?? undefined,
                );
              }
              completeTask(sId, filePath);
              addDownloadedId(sId);
              addToQueue({ id: sId, title: t.title, artist: t.artist, cover: t.cover, album: t.album, platform: 'kkbox' } as any);
              success++;
            } catch (e: any) {
              console.error('kkbox-detail batch download error:', e?.message || e);
              failTask(sId);
            }
          }
          setBatchDownloading(false);
          setBatchProgress(`下载完成 ${success}/${selected.length}`);
          setSelectMode(false);
          setSelectedIds(new Set());
        });
      } catch (e: any) {
        setBatchDownloading(false);
      }
    })();
  }, [tracks, selectedIds, ensureSession, addTask, updateProgress, completeTask, addDownloadedId, addToQueue, failTask]);

  const getItemLayout = useCallback(
    (_: any, index: number) => ({ length: ROW_H, offset: ROW_H * index, index }),
    [],
  );

  const renderItem = useCallback(
    ({ item, index }: { item: KkboxTrack; index: number }) => (
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
  ), [cover, title, artist, year, tracks.length, selectMode, selectedIds, batchDownloading, batchProgress, handleBatchDownload]);

  return (
    <SafeAreaView style={{ flex: 1, backgroundColor: '#0a0a1a' }} edges={['top']}>
      <View style={{ flex: 1 }}>
        <View style={{
          flexDirection: 'row', alignItems: 'center',
          paddingHorizontal: 12, paddingTop: 40, paddingBottom: 4,
        }}>
          <TouchableOpacity onPress={handleBack} activeOpacity={0.7} style={{ padding: 4 }}>
            <Ionicons name="chevron-back" size={22} color="rgba(255,255,255,0.7)" />
          </TouchableOpacity>
          <Text style={{ color: '#fff', fontSize: 16, fontWeight: '600', marginLeft: 8, flex: 1 }} numberOfLines={1}>
            {type === 'album' ? 'KKBOX专辑详情' : 'KKBOX单曲详情'}
          </Text>
          {selectMode && (
            <TouchableOpacity onPress={handleSelectAll} activeOpacity={0.7} style={{ padding: 4 }}>
              <Text style={{ color: '#818cf8', fontSize: 13, fontWeight: '500' }}>
                {selectedIds.size === tracks.length ? '取消全选' : '全选'}
              </Text>
            </TouchableOpacity>
          )}
          {type === 'album' && tracks.length > 1 && (
            <TouchableOpacity onPress={toggleSelectMode} activeOpacity={0.7} style={{ padding: 4 }}>
              <Text style={{ color: '#818cf8', fontSize: 13, fontWeight: '500' }}>
                {selectMode ? '取消' : '选择'}
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

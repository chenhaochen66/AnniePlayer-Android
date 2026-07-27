import React from 'react';
import { View, Text, FlatList } from 'react-native';
import Animated, { FadeInDown } from 'react-native-reanimated';
import type { Song } from '../types';
import { ResultItem } from './ResultItem';
import { SkeletonLoader } from './SkeletonLoader';
import { usePlayerStore } from '../store/usePlayerStore';
import { useSearchSelectionStore } from '../store/useSearchSelectionStore';

interface Props {
  kuwoSongs: Song[];
  neteaseSongs: Song[];
  kuwoLoading: boolean;
  neteaseLoading: boolean;
  query: string;
  onPlay: (song: Song, songs: Song[]) => void;
  onDownload: (song: Song) => void;
  onToggleSelect: (song: Song) => void;
}

export const SongSearchResults = React.memo(({ kuwoSongs, neteaseSongs, kuwoLoading, neteaseLoading, query, onPlay, onDownload, onToggleSelect }: Props) => {
  const activeSongId = usePlayerStore((s) => s.currentSong?.id);
  const selectedSongs = useSearchSelectionStore((s) => s.selectedSongs);

  const renderItem = React.useCallback(
    ({ item }: { item: Song }) => {
      const songs = item.platform === 'netease' ? neteaseSongs : kuwoSongs;
      const isActive = item.id === activeSongId;
      const isSelected = selectedSongs.some((s) => s.id === item.id);
      return (
        <ResultItem
          song={item}
          isActive={isActive}
          selected={isSelected}
          onPress={() => onPlay(item, songs)}
          onDownload={onDownload}
          onToggleSelect={onToggleSelect}
        />
      );
    },
    [kuwoSongs, neteaseSongs, activeSongId, selectedSongs, onPlay, onDownload, onToggleSelect],
  );

  if (kuwoLoading || neteaseLoading) {
    return <SkeletonLoader />;
  }

  if (kuwoSongs.length === 0 && neteaseSongs.length === 0) {
    if (query.length > 0) {
      return (
        <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center' }}>
          <Text style={{ color: 'rgba(255,255,255,0.3)', fontSize: 14 }}>未找到结果</Text>
        </View>
      );
    }
    return null;
  }

  return (
    <Animated.View entering={FadeInDown.duration(400)} key={`song-${query}`} style={{ flex: 1, flexDirection: 'row', paddingHorizontal: 8 }}>
      <View style={{ flex: 1 }}>
        {kuwoSongs.length > 0 ? (
          <FlatList
            data={kuwoSongs}
            renderItem={renderItem}
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
        {neteaseSongs.length > 0 ? (
          <FlatList
            data={neteaseSongs}
            renderItem={renderItem}
            keyExtractor={(item) => item.id}
            windowSize={5}
            maxToRenderPerBatch={15}
            initialNumToRender={10}
            removeClippedSubviews
            contentContainerStyle={{ paddingBottom: 120 }}
            showsVerticalScrollIndicator={false}
          />
        ) : query.length > 0 ? (
          <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center' }}>
            <Text style={{ color: 'rgba(255,255,255,0.3)', fontSize: 14 }}>未找到结果</Text>
          </View>
        ) : null}
      </View>
    </Animated.View>
  );
});

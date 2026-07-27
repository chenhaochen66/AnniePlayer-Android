import React from 'react';
import { View, Text, FlatList } from 'react-native';
import Animated, { FadeInDown } from 'react-native-reanimated';
import type { Album } from '../types';
import { AlbumCard } from './AlbumCard';
import { SkeletonLoader } from './SkeletonLoader';

interface Props {
  albums: Album[];
  loading: boolean;
  query: string;
  onAlbumPress: (album: Album) => void;
}

export const AlbumSearchResults = React.memo(({ albums, loading, query, onAlbumPress }: Props) => {
  if (loading) return <SkeletonLoader />;

  if (albums.length > 0) {
    return (
      <Animated.View entering={FadeInDown.duration(400)} key={`album-${query}`} style={{ flex: 1 }}>
        <FlatList
          data={albums}
          renderItem={({ item }) => <AlbumCard album={item} onPress={onAlbumPress} />}
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
    );
  }

  if (query.length > 0) {
    return (
      <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center' }}>
        <Text style={{ color: 'rgba(255,255,255,0.3)', fontSize: 14 }}>未找到结果</Text>
      </View>
    );
  }

  return null;
});

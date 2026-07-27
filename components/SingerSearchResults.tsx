import React from 'react';
import { View, Text, FlatList, Image, TouchableOpacity } from 'react-native';
import Animated, { FadeInDown } from 'react-native-reanimated';
import type { Artist } from '../types';
import { SkeletonLoader } from './SkeletonLoader';

interface Props {
  artists: Artist[];
  loading: boolean;
  query: string;
  onArtistPress: (artist: Artist) => void;
}

export const SingerSearchResults = React.memo(({ artists, loading, query, onArtistPress }: Props) => {
  if (loading) return <SkeletonLoader />;

  if (artists.length > 0) {
    return (
      <Animated.View entering={FadeInDown.duration(400)} key={`artist-${query}`} style={{ flex: 1 }}>
        <FlatList
          data={artists}
          renderItem={({ item }) => (
            <TouchableOpacity
              onPress={() => onArtistPress(item)}
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
          )}
          keyExtractor={(item) => item.id}
          windowSize={5}
          maxToRenderPerBatch={15}
          initialNumToRender={10}
          removeClippedSubviews
          contentContainerStyle={{ paddingBottom: 120, paddingTop: 4 }}
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

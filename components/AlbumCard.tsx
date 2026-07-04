import React, { useState } from 'react';
import { View, Text, Image, TouchableOpacity, Dimensions } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { Album } from '../types';
import { buildAlbumCoverUrl } from '../services/albumApi';

const { width: SCREEN_WIDTH } = Dimensions.get('window');
const GAP = 10;
const PADDING = 8;
const CARD_WIDTH = (SCREEN_WIDTH - PADDING * 2 - GAP) / 2;
const COVER_SIZE = CARD_WIDTH - 12;

interface AlbumCardProps {
  album: Album;
  onPress: (album: Album) => void;
}

export const AlbumCard = React.memo(({ album, onPress }: AlbumCardProps) => {
  const [imgError, setImgError] = useState(false);
  const coverUrl = buildAlbumCoverUrl(album);

  return (
    <TouchableOpacity
      onPress={() => onPress(album)}
      activeOpacity={0.7}
      style={{
        width: CARD_WIDTH,
        backgroundColor: 'rgba(255,255,255,0.03)',
        borderRadius: 12,
        overflow: 'hidden',
      }}
    >
      <View
        style={{
          width: COVER_SIZE,
          height: COVER_SIZE,
          marginHorizontal: 6,
          marginTop: 6,
          borderRadius: 8,
          backgroundColor: 'rgba(255,255,255,0.03)',
          alignItems: 'center',
          justifyContent: 'center',
          overflow: 'hidden',
        }}
      >
        {imgError || !coverUrl ? (
          <Ionicons name="disc" size={40} color="rgba(255,255,255,0.1)" />
        ) : (
          <Image
            source={{ uri: coverUrl }}
            style={{ width: COVER_SIZE, height: COVER_SIZE, borderRadius: 8 }}
            onError={() => setImgError(true)}
          />
        )}
      </View>
      <View style={{ paddingHorizontal: 8, paddingVertical: 8 }}>
        <Text
          style={{ color: '#fff', fontSize: 13, fontWeight: '600' }}
          numberOfLines={1}
        >
          {album.name}
        </Text>
        <Text
          style={{ color: 'rgba(255,255,255,0.4)', fontSize: 11, marginTop: 2 }}
          numberOfLines={1}
        >
          {album.artist}
        </Text>
        <View style={{ flexDirection: 'row', alignItems: 'center', marginTop: 4, gap: 4 }}>
          <Ionicons name="musical-note" size={10} color="rgba(255,255,255,0.2)" />
          <Text style={{ color: 'rgba(255,255,255,0.3)', fontSize: 10 }}>
            {album.musiccnt}首
          </Text>
          {album.pub ? (
            <Text style={{ color: 'rgba(255,255,255,0.2)', fontSize: 10, marginLeft: 4 }}>
              {album.pub}
            </Text>
          ) : null}
        </View>
      </View>
    </TouchableOpacity>
  );
});

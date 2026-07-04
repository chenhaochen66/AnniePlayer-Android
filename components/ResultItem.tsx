import React, { useState } from 'react';
import { View, Text, Image, TouchableOpacity } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { Song } from '../types';
import { useDownloadStore } from '../store/useDownloadStore';

interface ResultItemProps {
  song: Song;
  activeSongId?: string;
  onPress: (song: Song) => void;
  onDownload: (song: Song) => void;
}

export const ResultItem = React.memo(
  ({ song, activeSongId, onPress, onDownload }: ResultItemProps) => {
    const [imgError, setImgError] = useState(false);
    const downloadTask = useDownloadStore((s) => s.tasks[song.id]);
    const isActive = activeSongId === song.id;

    const isDownloading = downloadTask?.status === 'downloading' || downloadTask?.status === 'pending';

    return (
      <TouchableOpacity onPress={() => onPress(song)} activeOpacity={0.7}>
        <View
          style={{
            flexDirection: 'row',
            alignItems: 'center',
            paddingVertical: 8,
            paddingHorizontal: 8,
            backgroundColor: isActive ? 'rgba(129,140,248,0.06)' : 'transparent',
            borderRadius: 8,
          }}
        >
          <View style={{ width: 44, height: 44, borderRadius: 8, backgroundColor: 'rgba(255,255,255,0.03)', overflow: 'hidden', alignItems: 'center', justifyContent: 'center' }}>
            {imgError || !song.cover ? (
              <Ionicons name="musical-note" size={18} color="rgba(255,255,255,0.15)" />
            ) : (
              <Image
                source={{ uri: song.cover }}
                style={{ width: 44, height: 44, borderRadius: 8, opacity: isActive ? 0.6 : 1 }}
                onError={() => setImgError(true)}
              />
            )}
            {isActive && (
              <View style={{ position: 'absolute', width: 44, height: 44, alignItems: 'center', justifyContent: 'center' }}>
                <Ionicons name="play" size={16} color="#fff" />
              </View>
            )}
          </View>
          <View style={{ flex: 1, marginLeft: 10, marginRight: 4 }}>
            <Text
              style={{
                color: isActive ? '#818cf8' : '#fff',
                fontSize: 14,
                fontWeight: '600',
                marginBottom: 2,
              }}
              numberOfLines={1}
            >
              {song.title}
            </Text>
            <Text style={{ color: 'rgba(255,255,255,0.35)', fontSize: 12 }} numberOfLines={1}>
              {song.artist}
            </Text>
          </View>
          <TouchableOpacity onPress={() => onDownload(song)} activeOpacity={0.7} style={{ padding: 4 }}>
            {isDownloading ? (
              <View style={{
                width: 16, height: 16, borderRadius: 8,
                borderWidth: 2,
                borderColor: 'rgba(99,102,241,0.2)',
                borderTopColor: '#818cf8',
              }} />
            ) : (
              <Ionicons name="download-outline" size={18} color="rgba(255,255,255,0.5)" />
            )}
          </TouchableOpacity>
        </View>
      </TouchableOpacity>
    );
  },
  (prev, next) =>
    prev.song.id === next.song.id &&
    prev.activeSongId === next.activeSongId &&
    prev.onPress === next.onPress &&
    prev.onDownload === next.onDownload,
);

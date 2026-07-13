import React, { useCallback, useEffect } from 'react';
import { View, Text, FlatList, Image, TouchableOpacity, BackHandler, useWindowDimensions, StyleSheet } from 'react-native';
import Animated, { useSharedValue, useAnimatedStyle, withTiming, interpolate } from 'react-native-reanimated';
import { Ionicons } from '@expo/vector-icons';
import { useSearchSelectionStore } from '../store/useSearchSelectionStore';
import type { Song } from '../types';

const ROW_H = 60;

const SongRow = React.memo(({ item, onRemove }: { item: Song; onRemove: (id: string) => void }) => (
  <View style={styles.row}>
    <Image
      source={{ uri: item.cover }}
      style={{ width: 40, height: 40, borderRadius: 6, backgroundColor: 'rgba(255,255,255,0.03)' }}
    />
    <View style={{ flex: 1, marginLeft: 10 }}>
      <Text style={styles.title} numberOfLines={1}>{item.title}</Text>
      <Text style={styles.artist} numberOfLines={1}>{item.artist}</Text>
    </View>
    <TouchableOpacity onPress={() => onRemove(item.id)} activeOpacity={0.7} style={{ padding: 4 }}>
      <Ionicons name="close-circle" size={20} color="rgba(255,255,255,0.3)" />
    </TouchableOpacity>
  </View>
), (prev, next) => prev.item.id === next.item.id);

export const SearchSelectionOverlay = React.memo(() => {
  const { height: winH } = useWindowDimensions();
  const translateY = useSharedValue(winH);
  const visible = useSearchSelectionStore((s) => s.overlayVisible);
  const onClose = useCallback(() => useSearchSelectionStore.getState().setOverlayVisible(false), []);

  useEffect(() => {
    translateY.value = withTiming(visible ? 0 : winH, { duration: 200 });
  }, [visible, winH]);

  useEffect(() => {
    if (!visible) return;
    const sub = BackHandler.addEventListener('hardwareBackPress', () => { onClose(); return true; });
    return () => sub.remove();
  }, [visible, onClose]);

  const backdropStyle = useAnimatedStyle(() => ({
    opacity: interpolate(translateY.value, [0, Math.max(1, winH)], [1, 0]),
  }));

  const sheetStyle = useAnimatedStyle(() => ({ transform: [{ translateY: translateY.value }] }));

  const selectedSongs = useSearchSelectionStore((s) => s.selectedSongs);
  const removeSong = useSearchSelectionStore((s) => s.removeSong);

  const renderItem = useCallback(({ item }: { item: Song }) => (
    <SongRow item={item} onRemove={removeSong} />
  ), [removeSong]);

  return (
    <View style={StyleSheet.absoluteFill} pointerEvents={visible ? 'auto' : 'none'}>
      <Animated.View style={[{ flex: 1, backgroundColor: 'rgba(0,0,0,0.5)' }, backdropStyle]}>
        <TouchableOpacity activeOpacity={1} onPress={onClose} style={{ flex: 1 }} />
      </Animated.View>
      <Animated.View style={[styles.sheet, sheetStyle]}>
        <View style={styles.handle} />
        <View style={styles.header}>
          <Text style={styles.headerTitle}>
            待下载歌曲 ({selectedSongs.length})
          </Text>
          <TouchableOpacity onPress={onClose} activeOpacity={0.7} style={{ padding: 4 }}>
            <Ionicons name="close" size={22} color="rgba(255,255,255,0.7)" />
          </TouchableOpacity>
        </View>
        {selectedSongs.length === 0 ? (
          <View style={{ height: 120, alignItems: 'center', justifyContent: 'center' }}>
            <Text style={{ color: 'rgba(255,255,255,0.3)', fontSize: 14 }}>暂无选中歌曲</Text>
          </View>
        ) : (
          <FlatList
            data={selectedSongs}
            renderItem={renderItem}
            keyExtractor={(item) => item.id}
            getItemLayout={(_, i) => ({ length: ROW_H, offset: ROW_H * i, index: i })}
            style={{ maxHeight: 400 }}
            contentContainerStyle={{ paddingBottom: 16 }}
            showsVerticalScrollIndicator={false}
            removeClippedSubviews
            initialNumToRender={20}
            maxToRenderPerBatch={10}
            windowSize={3}
          />
        )}
      </Animated.View>
    </View>
  );
});

const styles = StyleSheet.create({
  sheet: {
    backgroundColor: '#1a1a2e',
    borderTopLeftRadius: 20,
    borderTopRightRadius: 20,
    paddingBottom: 32,
  },
  handle: {
    width: 36, height: 4, borderRadius: 2,
    backgroundColor: 'rgba(255,255,255,0.15)',
    alignSelf: 'center', marginTop: 12, marginBottom: 8,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
    paddingVertical: 12,
  },
  headerTitle: {
    color: '#fff',
    fontSize: 16,
    fontWeight: '700',
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 10,
    paddingHorizontal: 16,
    height: ROW_H,
  },
  title: {
    color: '#fff',
    fontSize: 14,
    fontWeight: '500',
  },
  artist: {
    color: 'rgba(255,255,255,0.35)',
    fontSize: 11,
    marginTop: 1,
  },
});

import React, { useState, useMemo, useCallback, useEffect } from 'react';
import {
  View,
  Text,
  FlatList,
  Image,
  TouchableOpacity,
  StyleSheet,
  Dimensions,
  BackHandler,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import Animated, { useSharedValue, useAnimatedStyle, withTiming } from 'react-native-reanimated';
import { Ionicons } from '@expo/vector-icons';
import { useDownloadStore } from '../store/useDownloadStore';
import { useUIStore } from '../store/useUIStore';
import { screenPadding } from '../constants/tokens';
import { DownloadTask, QUALITY_LABELS } from '../types';

const SCREEN_H = Dimensions.get('window').height;
const ITEM_H = 74;
type Tab = 'downloading' | 'completed';

const TaskItem = React.memo(({ item, onRemove }: { item: DownloadTask; onRemove: (id: string) => void }) => (
  <View style={styles.taskRow}>
    <Image source={{ uri: item.cover }} style={styles.taskCover} />
    <View style={{ flex: 1, marginLeft: 12 }}>
      <Text style={styles.taskTitle} numberOfLines={1}>{item.title}</Text>
      <Text style={styles.taskArtist} numberOfLines={1}>{item.artist}</Text>
      <View style={{ flexDirection: 'row', alignItems: 'center', marginTop: 2 }}>
        <Text style={{ color: 'rgba(255,255,255,0.2)', fontSize: 11 }}>
          {QUALITY_LABELS[item.quality]}
        </Text>
      </View>
      {item.status === 'downloading' && (
        <View style={{ flexDirection: 'row', alignItems: 'center', marginTop: 4 }}>
          <View style={styles.progressTrack}>
            <View style={[styles.progressFill, { width: `${item.progress}%` }]} />
          </View>
          <Text style={{ color: 'rgba(255,255,255,0.3)', fontSize: 11 }}>
            {Math.round(item.progress)}%
          </Text>
        </View>
      )}
    </View>
    <View style={{ marginLeft: 8 }}>
      {item.status === 'pending' && <View style={styles.spinner} />}
      {item.status === 'downloading' && (
        <Text style={{ color: '#818cf8', fontSize: 11, fontWeight: '600' }}>下载中</Text>
      )}
      {item.status === 'completed' && <Ionicons name="checkmark-circle" size={18} color="#4ade80" />}
      {item.status === 'error' && (
        <View style={{ alignItems: 'center' }}>
          <Ionicons name="alert-circle" size={18} color="#f87171" />
          <Text style={{ color: '#f87171', fontSize: 10, marginTop: 1 }}>失败</Text>
        </View>
      )}
    </View>
    {item.status === 'error' && (
      <TouchableOpacity onPress={() => onRemove(item.songId)} activeOpacity={0.7} style={{ marginLeft: 6, padding: 4 }}>
        <Ionicons name="close" size={14} color="rgba(255,255,255,0.2)" />
      </TouchableOpacity>
    )}
  </View>
));

export const DownloadsOverlay = React.memo(() => {
  const isOpen = useUIStore((s) => s.downloadQueueVisible);
  const onClose = useCallback(() => useUIStore.getState().setDownloadQueueVisible(false), []);

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

  const [tab, setTab] = useState<Tab>('downloading');
  const tasks = useDownloadStore((s) => s.tasks);
  const removeTask = useDownloadStore((s) => s.removeTask);

  const downloadingTasks = useMemo(
    () => Object.values(tasks).filter((t) => t.status !== 'completed'),
    [tasks],
  );

  const completedTasks = useMemo(
    () => Object.values(tasks).filter((t) => t.status === 'completed'),
    [tasks],
  );

  const handleRemove = useCallback((id: string) => removeTask(id), [removeTask]);

  const renderItem = useCallback(
    ({ item }: { item: DownloadTask }) => <TaskItem item={item} onRemove={handleRemove} />,
    [handleRemove],
  );

  const getItemLayout = useCallback(
    (_: any, index: number) => ({ length: ITEM_H, offset: ITEM_H * index, index }),
    [],
  );

  const currentData = tab === 'downloading' ? downloadingTasks : completedTasks;

  return (
    <View style={{ position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, zIndex: 210 }} pointerEvents={isOpen ? 'auto' : 'none'}>
      {isOpen && (
        <TouchableOpacity
          activeOpacity={1}
          onPress={onClose}
          style={{ flex: 1, backgroundColor: 'rgba(0,0,0,0.5)' }}
        />
      )}
      <Animated.View style={[{ position: 'absolute', top: 0, left: 0, right: 0, bottom: 0 }, animatedStyle]}>
        <SafeAreaView style={{ flex: 1, backgroundColor: '#0a0a1a' }} edges={['top']}>
          <View
            style={{
              flexDirection: 'row',
              alignItems: 'center',
              paddingHorizontal: screenPadding.horizontal,
              paddingTop: 40,
              paddingBottom: 8,
            }}
          >
            <TouchableOpacity onPress={onClose} activeOpacity={0.7} style={{ padding: 4, marginRight: 8 }}>
              <Ionicons name="arrow-back" size={22} color="rgba(255,255,255,0.7)" />
            </TouchableOpacity>
            <Text style={{ color: '#fff', fontSize: 20, fontWeight: '700' }}>下载任务</Text>
          </View>

          <View
            style={{
              flexDirection: 'row',
              marginHorizontal: screenPadding.horizontal,
              marginTop: 12,
              marginBottom: 8,
              backgroundColor: 'rgba(255,255,255,0.04)',
              borderRadius: 10,
              padding: 3,
            }}
          >
            {(['downloading', 'completed'] as const).map((t) => (
              <TouchableOpacity
                key={t}
                onPress={() => setTab(t)}
                activeOpacity={0.7}
                style={{
                  flex: 1,
                  paddingVertical: 8,
                  borderRadius: 8,
                  backgroundColor: tab === t ? 'rgba(129,140,248,0.15)' : 'transparent',
                  alignItems: 'center',
                }}
              >
                <Text
                  style={{
                    color: tab === t ? '#fff' : 'rgba(255,255,255,0.4)',
                    fontSize: 13,
                    fontWeight: '600',
                  }}
                >
                  {t === 'downloading' ? '下载中' : '已下载'}
                </Text>
              </TouchableOpacity>
            ))}
          </View>

          {currentData.length === 0 ? (
            <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center' }}>
              <Ionicons name="download-outline" size={48} color="rgba(255,255,255,0.06)" />
              <Text style={{ color: 'rgba(255,255,255,0.2)', fontSize: 16, marginTop: 12 }}>
                {tab === 'downloading' ? '暂无下载任务' : '暂无已下载歌曲'}
              </Text>
            </View>
          ) : (
            <FlatList
              data={currentData}
              renderItem={renderItem}
              keyExtractor={(item) => item.songId}
              getItemLayout={getItemLayout}
              removeClippedSubviews
              initialNumToRender={12}
              maxToRenderPerBatch={10}
              windowSize={5}
              contentContainerStyle={{ paddingBottom: 40 }}
            />
          )}
        </SafeAreaView>
      </Animated.View>
    </View>
  );
});

const styles = StyleSheet.create({
  taskRow: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 10,
    paddingHorizontal: screenPadding.horizontal,
    borderBottomWidth: 1,
    borderBottomColor: 'rgba(255,255,255,0.04)',
    height: ITEM_H,
  },
  taskCover: {
    width: 44,
    height: 44,
    borderRadius: 8,
    backgroundColor: 'rgba(255,255,255,0.03)',
  },
  taskTitle: {
    color: '#fff',
    fontSize: 14,
    fontWeight: '600',
  },
  taskArtist: {
    color: 'rgba(255,255,255,0.35)',
    fontSize: 12,
    marginTop: 1,
  },
  progressTrack: {
    flex: 1,
    height: 3,
    backgroundColor: 'rgba(255,255,255,0.06)',
    borderRadius: 1.5,
    marginRight: 8,
  },
  progressFill: {
    height: 3,
    backgroundColor: '#818cf8',
    borderRadius: 1.5,
  },
  spinner: {
    width: 14,
    height: 14,
    borderRadius: 7,
    borderWidth: 2,
    borderColor: 'rgba(99,102,241,0.2)',
    borderTopColor: '#818cf8',
  },
});

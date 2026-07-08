import React, { useEffect, useCallback } from 'react';
import { View, Text, TouchableOpacity, FlatList, useWindowDimensions, BackHandler } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import Animated, {
  useAnimatedStyle,
  useSharedValue,
  withTiming,
  Easing,
} from 'react-native-reanimated';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { usePlayerStore } from '../store/usePlayerStore';
import { useUIStore } from '../store/useUIStore';

export const QueueModal = React.memo(() => {
  const { height: winH } = useWindowDimensions();
  const panelHeight = winH * 0.6;
  const visible = useUIStore((s) => s.queueVisible);
  const onClose = useCallback(() => useUIStore.getState().setQueueVisible(false), []);
  const { bottom } = useSafeAreaInsets();
  const queue = usePlayerStore((s) => s.queue);
  const currentIndex = usePlayerStore((s) => s.currentIndex);
  const playIndex = usePlayerStore((s) => s.playIndex);
  const removeFromQueue = usePlayerStore((s) => s.removeFromQueue);
  const translateY = useSharedValue(panelHeight);
  const overlayOpacity = useSharedValue(0);

  useEffect(() => {
    const config = { duration: 150, easing: Easing.out(Easing.cubic) };
    if (visible) {
      translateY.value = withTiming(0, config);
      overlayOpacity.value = withTiming(1, config);
    } else {
      translateY.value = withTiming(panelHeight, config);
      overlayOpacity.value = withTiming(0, config);
    }
  }, [visible, panelHeight]);

  useEffect(() => {
    if (!visible) return;
    const sub = BackHandler.addEventListener('hardwareBackPress', () => {
      onClose();
      return true;
    });
    return () => sub.remove();
  }, [visible, onClose]);

  const panelStyle = useAnimatedStyle(() => ({
    transform: [{ translateY: translateY.value }],
  }));

  const overlayStyle = useAnimatedStyle(() => ({
    opacity: overlayOpacity.value,
  }));

  const renderItem = useCallback(
    ({ item, index }: { item: any; index: number }) => {
      const isCurrent = index === currentIndex;
      return (
        <TouchableOpacity
          activeOpacity={0.7}
          onPress={() => { playIndex(index); onClose(); }}
          style={{
            flexDirection: 'row',
            alignItems: 'center',
            paddingVertical: 8,
            paddingHorizontal: 20,
            backgroundColor: isCurrent ? 'rgba(99,102,241,0.08)' : 'transparent',
          }}
        >
          <View style={{ width: 28, alignItems: 'center' }}>
            <Text style={{
              color: isCurrent ? '#818cf8' : 'rgba(255,255,255,0.25)',
              fontSize: 14,
              fontWeight: isCurrent ? '700' : '500',
            }}>
              {index + 1}
            </Text>
          </View>
          <View style={{ flex: 1, marginLeft: 12 }}>
            <Text
              style={{
                color: isCurrent ? '#818cf8' : '#fff',
                fontSize: 14,
                fontWeight: isCurrent ? '700' : '500',
              }}
              numberOfLines={1}
            >
              {isCurrent ? '正在播放 ' : ''}{item.title}
            </Text>
            <Text style={{ color: 'rgba(255,255,255,0.3)', fontSize: 11, marginTop: 1 }} numberOfLines={1}>
              {item.artist}
            </Text>
          </View>
          {queue.length > 1 && (
            <TouchableOpacity
              hitSlop={{ top: 8, bottom: 8, left: 8, right: 8 }}
              onPress={() => removeFromQueue(index)}
              activeOpacity={0.7}
              style={{ padding: 4 }}
            >
              <Ionicons name="close" size={16} color="rgba(255,255,255,0.2)" />
            </TouchableOpacity>
          )}
        </TouchableOpacity>
      );
    },
    [currentIndex, playIndex, onClose, queue.length, removeFromQueue],
  );

  return (
    <View style={{ position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, zIndex: 200 }} pointerEvents={visible ? 'auto' : 'none'}>
      <Animated.View style={[{ flex: 1, backgroundColor: 'rgba(0,0,0,0.6)' }, overlayStyle]}>
        <TouchableOpacity activeOpacity={1} onPress={onClose} style={{ flex: 1 }} />
      </Animated.View>
      <Animated.View
        style={[{
          position: 'absolute',
          bottom: 0,
          left: 0,
          right: 0,
          backgroundColor: '#1a1a2e',
          borderTopLeftRadius: 20,
          borderTopRightRadius: 20,
          maxHeight: panelHeight,
          paddingTop: 20,
          paddingBottom: bottom + 16,
        }, panelStyle]}
      >
        <View style={{ width: 36, height: 4, borderRadius: 2, backgroundColor: 'rgba(255,255,255,0.2)', alignSelf: 'center', marginBottom: 16 }} />
        <View style={{ flexDirection: 'row', alignItems: 'center', paddingHorizontal: 20, marginBottom: 12 }}>
          <Text style={{ color: '#fff', fontSize: 17, fontWeight: '700', flex: 1 }}>
            播放队列 ({queue.length})
          </Text>
          <TouchableOpacity onPress={onClose} activeOpacity={0.7} style={{ padding: 4 }}>
            <Ionicons name="close" size={20} color="rgba(255,255,255,0.5)" />
          </TouchableOpacity>
        </View>
        {visible && (queue.length === 0 ? (
          <View style={{ alignItems: 'center', paddingVertical: 40 }}>
            <Ionicons name="musical-notes-outline" size={36} color="rgba(255,255,255,0.08)" />
            <Text style={{ color: 'rgba(255,255,255,0.2)', fontSize: 14, marginTop: 8 }}>队列为空</Text>
          </View>
        ) : (
          <FlatList
            data={queue}
            keyExtractor={(item) => item.id}
            renderItem={renderItem}
            extraData={currentIndex}
            contentContainerStyle={{ paddingBottom: 8 }}
          />
        ))}
      </Animated.View>
    </View>
  );
});

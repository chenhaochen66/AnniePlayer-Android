import React, { useEffect, useState, useCallback, memo } from 'react';
import {
  View,
  Text,
  TouchableOpacity,
  ScrollView,
  Dimensions,
  Alert,
  BackHandler,
  Linking,
} from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import Animated, {
  useAnimatedStyle,
  useSharedValue,
  withTiming,
} from 'react-native-reanimated';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useUIStore } from '../store/useUIStore';
import { clearAllCache, getFileCacheSize } from '../services/assetProxy';

const { width: SCREEN_WIDTH } = Dimensions.get('window');
const DRAWER_WIDTH = SCREEN_WIDTH * 0.72;

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

export const MenuDrawer = React.memo(() => {
  const insets = useSafeAreaInsets();
  const isOpen = useUIStore((s) => s.drawerVisible);
  const onClose = useCallback(() => useUIStore.getState().setDrawerVisible(false), []);
  const [cacheSize, setCacheSize] = useState(0);

  const translateX = useSharedValue(-DRAWER_WIDTH);

  useEffect(() => {
    translateX.value = withTiming(isOpen ? 0 : -DRAWER_WIDTH, { duration: 150 });
  }, [isOpen]);

  useEffect(() => {
    if (!isOpen) return;
    const onBack = () => { onClose(); return true; };
    const sub = BackHandler.addEventListener('hardwareBackPress', onBack);
    return () => sub.remove();
  }, [isOpen, onClose]);

  useEffect(() => {
    if (!isOpen) return;
    getFileCacheSize().then(setCacheSize);
  }, [isOpen]);

  const drawerStyle = useAnimatedStyle(() => ({
    transform: [{ translateX: translateX.value }],
  }));

  const handleClearCache = useCallback(() => {
    Alert.alert('清除缓存', `将清除 ${formatSize(cacheSize)} 的缓存数据（包括播放缓存、下载残留、API缓存），确定吗？`, [
      { text: '取消', style: 'cancel' },
      {
        text: '确定',
        style: 'destructive',
        onPress: async () => {
          await clearAllCache();
          setCacheSize(0);
        },
      },
    ]);
  }, [cacheSize]);

  return (
    <View style={{ position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, zIndex: 200, display: isOpen ? 'flex' : 'none' }}>
      {isOpen && (
        <TouchableOpacity
          activeOpacity={1}
          onPress={onClose}
          style={{ flex: 1, backgroundColor: 'rgba(0,0,0,0.5)' }}
        />
      )}
      <Animated.View
        style={[
          {
            position: 'absolute',
            top: 0,
            left: 0,
            width: DRAWER_WIDTH,
            height: '100%',
            backgroundColor: '#12122a',
            paddingTop: 40 + insets.top,
          },
          drawerStyle,
        ]}
      >
        <ScrollView showsVerticalScrollIndicator={false} contentContainerStyle={{ paddingBottom: 90, paddingHorizontal: 20 }}>
          <View style={{ flexDirection: 'row', alignItems: 'center', marginBottom: 32 }}>
            <TouchableOpacity onPress={onClose} activeOpacity={0.7} style={{ padding: 4 }}>
              <Ionicons name="close" size={22} color="rgba(255,255,255,0.7)" />
            </TouchableOpacity>
            <Text style={{ color: '#fff', fontSize: 20, fontWeight: '700', marginLeft: 12 }}>
              菜单
            </Text>
          </View>

          <Text style={{ color: 'rgba(255,255,255,0.3)', fontSize: 12, fontWeight: '600', marginBottom: 12, letterSpacing: 0.5 }}>
            账号配置
          </Text>

          <TouchableOpacity
            onPress={() => useUIStore.getState().setQobuzTokenVisible(true)}
            activeOpacity={0.7}
            style={{
              flexDirection: 'row',
              alignItems: 'center',
              backgroundColor: 'rgba(255,255,255,0.03)',
              borderRadius: 12,
              padding: 16,
              marginBottom: 12,
            }}
          >
            <Ionicons name="key-outline" size={18} color="rgba(255,255,255,0.5)" />
            <Text style={{ color: '#fff', fontSize: 15, fontWeight: '600', marginLeft: 10, flex: 1 }}>
              Qobuz Token 管理
            </Text>
            <Ionicons name="chevron-forward" size={16} color="rgba(255,255,255,0.2)" />
          </TouchableOpacity>

          <TouchableOpacity
            onPress={() => useUIStore.getState().setKkboxAccountVisible(true)}
            activeOpacity={0.7}
            style={{
              flexDirection: 'row',
              alignItems: 'center',
              backgroundColor: 'rgba(255,255,255,0.03)',
              borderRadius: 12,
              padding: 16,
              marginBottom: 12,
            }}
          >
            <Ionicons name="key-outline" size={18} color="rgba(255,255,255,0.5)" />
            <Text style={{ color: '#fff', fontSize: 15, fontWeight: '600', marginLeft: 10, flex: 1 }}>
              KKBOX 账号管理
            </Text>
            <Ionicons name="chevron-forward" size={16} color="rgba(255,255,255,0.2)" />
          </TouchableOpacity>

          <Text style={{ color: 'rgba(255,255,255,0.3)', fontSize: 12, fontWeight: '600', marginBottom: 12, letterSpacing: 0.5 }}>
            下载
          </Text>

          <TouchableOpacity
            onPress={() => {
              useUIStore.getState().setDownloadQueueVisible(true);
            }}
            activeOpacity={0.7}
            style={{
              flexDirection: 'row',
              alignItems: 'center',
              backgroundColor: 'rgba(255,255,255,0.03)',
              borderRadius: 12,
              padding: 16,
              marginBottom: 12,
            }}
          >
            <Ionicons name="list-outline" size={18} color="rgba(255,255,255,0.5)" />
            <Text style={{ color: '#fff', fontSize: 15, fontWeight: '600', marginLeft: 10, flex: 1 }}>
              下载任务
            </Text>
            <Ionicons name="chevron-forward" size={16} color="rgba(255,255,255,0.2)" />
          </TouchableOpacity>

          <View style={{ backgroundColor: 'rgba(255,255,255,0.03)', borderRadius: 12, padding: 16, marginBottom: 12 }}>
            <View style={{ flexDirection: 'row', alignItems: 'center', marginBottom: 8 }}>
              <Ionicons name="folder-outline" size={18} color="rgba(255,255,255,0.5)" />
              <Text style={{ color: '#fff', fontSize: 15, fontWeight: '600', marginLeft: 10 }}>
                保存位置
              </Text>
            </View>
            <Text style={{ color: 'rgba(255,255,255,0.35)', fontSize: 13, lineHeight: 20 }}>
              音频文件自动保存到 Music/Annieplayer 文件夹。
            </Text>
          </View>

          <Text style={{ color: 'rgba(255,255,255,0.3)', fontSize: 12, fontWeight: '600', marginBottom: 12, letterSpacing: 0.5 }}>
            缓存
          </Text>

          <TouchableOpacity
            onPress={handleClearCache}
            activeOpacity={0.7}
            style={{
              flexDirection: 'row',
              alignItems: 'center',
              backgroundColor: 'rgba(255,255,255,0.03)',
              borderRadius: 12,
              padding: 16,
            }}
          >
            <Ionicons name="trash-outline" size={18} color="rgba(255,255,255,0.5)" />
            <View style={{ flex: 1, marginLeft: 10 }}>
              <Text style={{ color: '#fff', fontSize: 15, fontWeight: '600' }}>
                清除缓存
              </Text>
              <Text style={{ color: 'rgba(255,255,255,0.3)', fontSize: 12, marginTop: 1 }}>
                {cacheSize > 0 ? `当前缓存 ${formatSize(cacheSize)}` : '暂无缓存'}
              </Text>
            </View>
            <Ionicons name="chevron-forward" size={16} color="rgba(255,255,255,0.2)" />
          </TouchableOpacity>

          <Text style={{ color: 'rgba(255,255,255,0.3)', fontSize: 12, fontWeight: '600', marginBottom: 12, letterSpacing: 0.5, marginTop: 8 }}>
            关于
          </Text>

          <TouchableOpacity
            onPress={() => Linking.openURL('https://www.hifiindex.tech/')}
            activeOpacity={0.7}
            style={{
              flexDirection: 'row',
              alignItems: 'center',
              backgroundColor: 'rgba(255,255,255,0.03)',
              borderRadius: 12,
              padding: 16,
            }}
          >
            <Ionicons name="globe-outline" size={18} color="rgba(255,255,255,0.5)" />
            <View style={{ flex: 1, marginLeft: 10 }}>
              <Text style={{ color: '#fff', fontSize: 15, fontWeight: '600' }}>
                HIFI 科普网站
              </Text>
              <Text style={{ color: 'rgba(255,255,255,0.3)', fontSize: 11, marginTop: 1 }}>
                by@买葱花也要砍一刀(2801497426)
              </Text>
            </View>
            <Ionicons name="open-outline" size={16} color="rgba(255,255,255,0.2)" />
          </TouchableOpacity>

          <TouchableOpacity
            onPress={() => Linking.openURL('https://zhou1019-1.github.io/spek-lossless-detector/')}
            activeOpacity={0.7}
            style={{
              flexDirection: 'row',
              alignItems: 'center',
              backgroundColor: 'rgba(255,255,255,0.03)',
              borderRadius: 12,
              padding: 16,
              marginTop: 8,
            }}
          >
            <Ionicons name="globe-outline" size={18} color="rgba(255,255,255,0.5)" />
            <View style={{ flex: 1, marginLeft: 10 }}>
              <Text style={{ color: '#fff', fontSize: 15, fontWeight: '600' }}>
                频谱分析网站
              </Text>
              <Text style={{ color: 'rgba(255,255,255,0.3)', fontSize: 11, marginTop: 1 }}>
                by@HIFI神医章鱼哥(430768125)
              </Text>
            </View>
            <Ionicons name="open-outline" size={16} color="rgba(255,255,255,0.2)" />
          </TouchableOpacity>

          <TouchableOpacity
            onPress={() => useUIStore.getState().setContactVisible(true)}
            activeOpacity={0.7}
            style={{
              flexDirection: 'row',
              alignItems: 'center',
              backgroundColor: 'rgba(255,255,255,0.03)',
              borderRadius: 12,
              padding: 16,
              marginTop: 8,
            }}
          >
            <Ionicons name="mail-outline" size={18} color="rgba(255,255,255,0.5)" />
            <Text style={{ color: '#fff', fontSize: 15, fontWeight: '600', marginLeft: 10, flex: 1 }}>
              联系我们
            </Text>
            <Ionicons name="chevron-forward" size={16} color="rgba(255,255,255,0.2)" />
          </TouchableOpacity>
        </ScrollView>
      </Animated.View>
    </View>
  );
});

export const DrawerController = memo(() => {
  const setDrawerVisible = useUIStore((s) => s.setDrawerVisible);
  return (
    <TouchableOpacity onPress={() => setDrawerVisible(true)} activeOpacity={0.7} style={{ padding: 4, marginRight: 8 }}>
      <Ionicons name="menu" size={24} color="rgba(255,255,255,0.7)" />
    </TouchableOpacity>
  );
});


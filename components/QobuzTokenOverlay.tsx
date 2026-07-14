import React, { useState, useCallback, useEffect } from 'react';
import {
  View, Text, FlatList, TouchableOpacity, TextInput,
  Dimensions, BackHandler, Alert,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import Animated, { useSharedValue, useAnimatedStyle, withTiming } from 'react-native-reanimated';
import { Ionicons } from '@expo/vector-icons';
import { useUIStore } from '../store/useUIStore';
import { useQobuzStore } from '../store/useQobuzStore';
import { checkToken } from '../services/qobuzApi';
import { screenPadding } from '../constants/tokens';
import type { QobuzToken } from '../types';

const { height: SCREEN_H } = Dimensions.get('window');
const ITEM_H = 90;

function maskToken(token: string): string {
  if (token.length <= 8) return '****';
  return token.slice(0, 4) + '****' + token.slice(-4);
}

const TokenItem = React.memo(({ item, checkingId, onCheck, onSelect, onEdit, onRemove }: {
  item: QobuzToken; checkingId: string | null;
  onCheck: (t: QobuzToken) => void; onSelect: (id: string) => void;
  onEdit: (t: QobuzToken) => void; onRemove: (id: string) => void;
}) => (
  <View style={{
    flexDirection: 'row', alignItems: 'center',
    paddingVertical: 14, paddingHorizontal: screenPadding.horizontal,
    height: ITEM_H, borderBottomWidth: 1, borderBottomColor: 'rgba(255,255,255,0.04)',
    backgroundColor: item.isActive ? 'rgba(74,222,128,0.04)' : 'transparent',
  }}>
    {item.isActive && <View style={{ position: 'absolute', left: 0, top: 18, width: 3, height: 54, backgroundColor: '#4ade80', borderTopRightRadius: 2, borderBottomRightRadius: 2 }} />}
    <TouchableOpacity onPress={() => onCheck(item)} activeOpacity={0.7} style={{ padding: 8, marginRight: 10, alignItems: 'center' }}>
      {checkingId === item.id ? (
        <View style={{ width: 22, height: 22, alignItems: 'center', justifyContent: 'center' }}>
          <Text style={{ color: '#818cf8', fontSize: 10 }}>...</Text>
        </View>
      ) : (
        <>
          <Ionicons name="search-outline" size={22} color="rgba(255,255,255,0.4)" />
          <Text style={{ color: 'rgba(255,255,255,0.3)', fontSize: 10, marginTop: 2 }}>验证</Text>
        </>
      )}
    </TouchableOpacity>
    <TouchableOpacity onPress={() => onSelect(item.id)} activeOpacity={0.7} style={{ flex: 1 }}>
      <View style={{ flexDirection: 'row', alignItems: 'center' }}>
        <Text style={{ color: '#fff', fontSize: 17, fontWeight: '600' }} numberOfLines={1}>{item.name}</Text>
        {item.isActive && (
          <View style={{ marginLeft: 8, backgroundColor: 'rgba(129,140,248,0.15)', paddingHorizontal: 8, paddingVertical: 3, borderRadius: 4 }}>
            <Text style={{ color: '#818cf8', fontSize: 12, fontWeight: '600' }}>使用中</Text>
          </View>
        )}
      </View>
      <Text style={{ color: 'rgba(255,255,255,0.3)', fontSize: 14, marginTop: 3 }}>{maskToken(item.token)}</Text>
    </TouchableOpacity>
    <TouchableOpacity onPress={() => onEdit(item)} activeOpacity={0.7} style={{ padding: 6, marginLeft: 4 }}>
      <Ionicons name="pencil-outline" size={20} color="rgba(255,255,255,0.35)" />
    </TouchableOpacity>
    <TouchableOpacity onPress={() => onRemove(item.id)} activeOpacity={0.7} style={{ padding: 6 }}>
      <Ionicons name="trash-outline" size={20} color="rgba(255,255,255,0.25)" />
    </TouchableOpacity>
  </View>
));

export const QobuzTokenOverlay = React.memo(() => {
  const isOpen = useUIStore((s) => s.qobuzTokenVisible);
  const onClose = useCallback(() => useUIStore.getState().setQobuzTokenVisible(false), []);
  const tokens = useQobuzStore((s) => s.tokens);
  const addToken = useQobuzStore((s) => s.addToken);
  const updateToken = useQobuzStore((s) => s.updateToken);
  const removeToken = useQobuzStore((s) => s.removeToken);
  const setActiveToken = useQobuzStore((s) => s.setActiveToken);
  const [showAdd, setShowAdd] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [newName, setNewName] = useState('');
  const [newToken, setNewToken] = useState('');
  const [checkingId, setCheckingId] = useState<string | null>(null);

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

  const overlayStyle = useAnimatedStyle(() => ({ transform: [{ translateY: translateY.value }] }));

  const handleAdd = useCallback(() => {
    const name = newName.trim();
    const token = newToken.trim();
    if (!name || !token) { Alert.alert('提示', '请填写名称和 Token'); return; }
    if (editingId) {
      updateToken(editingId, name, token);
    } else {
      addToken(name, token);
    }
    setNewName('');
    setNewToken('');
    setShowAdd(false);
    setEditingId(null);
  }, [newName, newToken, addToken, updateToken, editingId]);

  const handleEdit = useCallback((item: QobuzToken) => {
    setEditingId(item.id);
    setNewName(item.name);
    setNewToken(item.token);
    setShowAdd(true);
  }, []);

  const handleCancelAdd = useCallback(() => {
    setShowAdd(false);
    setEditingId(null);
    setNewName('');
    setNewToken('');
  }, []);

  const handleCheck = useCallback(async (item: QobuzToken) => {
    setCheckingId(item.id);
    try {
      const result = await checkToken(item.token);
      Alert.alert(result.valid ? '有效' : '无效', result.valid ? `地区: ${result.region}` : 'Token 无效或已过期');
    } catch (e: any) {
      Alert.alert('检测失败', e.message || '网络错误');
    } finally {
      setCheckingId(null);
    }
  }, []);

  const renderItem = useCallback(
    ({ item }: { item: QobuzToken }) => (
      <TokenItem
        item={item} checkingId={checkingId} onCheck={handleCheck} onSelect={setActiveToken}
        onEdit={handleEdit}
        onRemove={(id) => {
          Alert.alert('删除 Token', `确定删除 "${item.name}" 吗？`, [
            { text: '取消', style: 'cancel' },
            { text: '删除', style: 'destructive', onPress: () => removeToken(id) },
          ]);
        }}
      />
    ),
    [checkingId, handleCheck, setActiveToken, handleEdit, removeToken],
  );

  return (
    <View style={{ position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, zIndex: 210, display: isOpen ? 'flex' : 'none' }}>
      {isOpen && (
        <TouchableOpacity activeOpacity={1} onPress={onClose} style={{ flex: 1, backgroundColor: 'rgba(0,0,0,0.5)' }} />
      )}
      <Animated.View style={[{ position: 'absolute', top: 0, left: 0, right: 0, bottom: 0 }, overlayStyle]}>
        <SafeAreaView style={{ flex: 1, backgroundColor: '#0a0a1a' }} edges={['top']}>
          <View style={{
            flexDirection: 'row', alignItems: 'center',
            paddingHorizontal: screenPadding.horizontal, paddingTop: 40, paddingBottom: 8,
          }}>
            <TouchableOpacity onPress={onClose} activeOpacity={0.7} style={{ padding: 4, marginRight: 8 }}>
              <Ionicons name="arrow-back" size={22} color="rgba(255,255,255,0.7)" />
            </TouchableOpacity>
            <Text style={{ color: '#fff', fontSize: 20, fontWeight: '700', flex: 1 }}>Qobuz Token 管理</Text>
            <TouchableOpacity onPress={() => { if (editingId) { handleCancelAdd(); } else { setShowAdd(!showAdd); } }} activeOpacity={0.7} style={{ padding: 4 }}>
              <Ionicons name={showAdd ? "close" : "add"} size={24} color="#818cf8" />
            </TouchableOpacity>
          </View>

          {showAdd && (
            <View style={{ marginHorizontal: screenPadding.horizontal, backgroundColor: 'rgba(255,255,255,0.03)', borderRadius: 12, padding: 16, marginBottom: 12 }}>
              <TextInput value={newName} onChangeText={setNewName} placeholder="名称（如：主账号）" placeholderTextColor="rgba(255,255,255,0.3)"
                style={{ color: '#fff', fontSize: 15, backgroundColor: 'rgba(255,255,255,0.05)', borderRadius: 8, padding: 12, marginBottom: 8 }} />
              <TextInput value={newToken} onChangeText={setNewToken} placeholder="Token" placeholderTextColor="rgba(255,255,255,0.3)"
                autoCapitalize="none" autoCorrect={false}
                style={{ color: '#fff', fontSize: 15, backgroundColor: 'rgba(255,255,255,0.05)', borderRadius: 8, padding: 12, marginBottom: 12 }} />
            <TouchableOpacity onPress={handleAdd} activeOpacity={0.7}
              style={{ backgroundColor: '#818cf8', borderRadius: 8, paddingVertical: 10, alignItems: 'center' }}>
              <Text style={{ color: '#fff', fontSize: 15, fontWeight: '600' }}>{editingId ? '更新' : '保存'}</Text>
            </TouchableOpacity>
          </View>
          )}

          {tokens.length === 0 ? (
            <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center' }}>
              <Ionicons name="key-outline" size={48} color="rgba(255,255,255,0.06)" />
              <Text style={{ color: 'rgba(255,255,255,0.2)', fontSize: 16, marginTop: 12 }}>暂无 Token，点击右上角添加</Text>
            </View>
          ) : (
            <FlatList
              data={tokens}
              renderItem={renderItem}
              keyExtractor={(item) => item.id}
              getItemLayout={(_, index) => ({ length: ITEM_H, offset: ITEM_H * index, index })}
              removeClippedSubviews
              initialNumToRender={12}
              maxToRenderPerBatch={10}
              windowSize={5}
                contentContainerStyle={{ paddingBottom: 100 }}
            />
          )}
        </SafeAreaView>
      </Animated.View>
    </View>
  );
});

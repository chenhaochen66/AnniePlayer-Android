import React, { useState, useCallback, useEffect } from 'react';
import {
  View, Text, FlatList, TouchableOpacity, TextInput,
  Dimensions, BackHandler, Alert,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import Animated, { useSharedValue, useAnimatedStyle, withTiming } from 'react-native-reanimated';
import { Ionicons } from '@expo/vector-icons';
import { useUIStore } from '../store/useUIStore';
import { useKkboxStore } from '../store/useKkboxStore';
import { login as kkboxLogin } from '../services/platforms/kkboxApi';
import { screenPadding } from '../constants/tokens';
import type { KkboxAccount } from '../types';

const { height: SCREEN_H } = Dimensions.get('window');
const ITEM_H = 90;

function maskEmail(email: string): string {
  if (email.length <= 6) return '****';
  const at = email.indexOf('@');
  if (at <= 1) return email.slice(0, 1) + '****' + email.slice(at);
  return email.slice(0, 2) + '****' + email.slice(at);
}

const AccountItem = React.memo(({ item, loginLoadingId, onLogin, onSelect, onEdit, onRemove }: {
  item: KkboxAccount; loginLoadingId: string | null;
  onLogin: (a: KkboxAccount) => void; onSelect: (id: string) => void;
  onEdit: (a: KkboxAccount) => void; onRemove: (id: string) => void;
}) => (
  <View style={{
    flexDirection: 'row', alignItems: 'center',
    paddingVertical: 14, paddingHorizontal: screenPadding.horizontal,
    height: ITEM_H, borderBottomWidth: 1, borderBottomColor: 'rgba(255,255,255,0.04)',
    backgroundColor: item.isActive ? 'rgba(74,222,128,0.04)' : 'transparent',
  }}>
    {item.isActive && <View style={{ position: 'absolute', left: 0, top: 18, width: 3, height: 54, backgroundColor: '#4ade80', borderTopRightRadius: 2, borderBottomRightRadius: 2 }} />}
    <TouchableOpacity onPress={() => onLogin(item)} activeOpacity={0.7} style={{ padding: 8, marginRight: 10, alignItems: 'center' }}>
      {loginLoadingId === item.id ? (
        <View style={{ width: 22, height: 22, alignItems: 'center', justifyContent: 'center' }}>
          <Text style={{ color: '#818cf8', fontSize: 10 }}>...</Text>
        </View>
      ) : (
        <>
          <Ionicons name={item.session?.loggedIn ? "refresh-outline" : "log-in-outline"} size={22} color="rgba(255,255,255,0.4)" />
          <Text style={{ color: 'rgba(255,255,255,0.3)', fontSize: 10, marginTop: 2 }}>{item.session?.loggedIn ? '续期' : '登录'}</Text>
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
      <Text style={{ color: 'rgba(255,255,255,0.3)', fontSize: 14, marginTop: 3 }}>
        {item.session?.loggedIn ? maskEmail(item.email) : '未登录'}
      </Text>
    </TouchableOpacity>
    <TouchableOpacity onPress={() => onEdit(item)} activeOpacity={0.7} style={{ padding: 6, marginLeft: 4 }}>
      <Ionicons name="pencil-outline" size={20} color="rgba(255,255,255,0.35)" />
    </TouchableOpacity>
    <TouchableOpacity onPress={() => onRemove(item.id)} activeOpacity={0.7} style={{ padding: 6 }}>
      <Ionicons name="trash-outline" size={20} color="rgba(255,255,255,0.25)" />
    </TouchableOpacity>
  </View>
));

export const KkboxAccountOverlay = React.memo(() => {
  const isOpen = useUIStore((s) => s.kkboxAccountVisible);
  const onClose = useCallback(() => useUIStore.getState().setKkboxAccountVisible(false), []);
  const accounts = useKkboxStore((s) => s.accounts);
  const addAccount = useKkboxStore((s) => s.addAccount);
  const updateAccount = useKkboxStore((s) => s.updateAccount);
  const removeAccount = useKkboxStore((s) => s.removeAccount);
  const setActiveAccount = useKkboxStore((s) => s.setActiveAccount);
  const updateAccountSession = useKkboxStore((s) => s.updateAccountSession);
  const [showAdd, setShowAdd] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [newName, setNewName] = useState('');
  const [newEmail, setNewEmail] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [loginLoadingId, setLoginLoadingId] = useState<string | null>(null);

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

  const handleSave = useCallback(() => {
    const name = newName.trim();
    const email = newEmail.trim();
    const password = newPassword;
    if (!name || !email || !password) { Alert.alert('提示', '请填写名称、邮箱和密码'); return; }
    if (editingId) {
      updateAccount(editingId, name, email, password);
    } else {
      addAccount(name, email, password, { sid: '', licContentKey: '', highQuality: false, kkid: '', email, loggedIn: false });
    }
    setNewName('');
    setNewEmail('');
    setNewPassword('');
    setShowAdd(false);
    setEditingId(null);
  }, [newName, newEmail, newPassword, addAccount, updateAccount, editingId]);

  const handleEdit = useCallback((item: KkboxAccount) => {
    setEditingId(item.id);
    setNewName(item.name);
    setNewEmail(item.email);
    setNewPassword(item.password);
    setShowAdd(true);
  }, []);

  const handleCancelAdd = useCallback(() => {
    setShowAdd(false);
    setEditingId(null);
    setNewName('');
    setNewEmail('');
    setNewPassword('');
  }, []);

  const handleLogin = useCallback(async (item: KkboxAccount) => {
    setLoginLoadingId(item.id);
    try {
      const result = await kkboxLogin(item.email, item.password);
      updateAccountSession(item.id, {
        sid: result.sid, licContentKey: result.licContentKey,
        highQuality: result.highQuality, kkid: result.kkid,
        email: item.email, loggedIn: true,
      });
      if (!item.isActive) setActiveAccount(item.id);
      Alert.alert('成功', 'KKBOX 登录成功');
    } catch (e: any) {
      Alert.alert('登录失败', e.message || '网络错误');
    } finally {
      setLoginLoadingId(null);
    }
  }, [updateAccountSession, setActiveAccount]);

  const renderItem = useCallback(
    ({ item }: { item: KkboxAccount }) => (
      <AccountItem
        item={item} loginLoadingId={loginLoadingId} onLogin={handleLogin} onSelect={setActiveAccount}
        onEdit={handleEdit}
        onRemove={(id) => {
          Alert.alert('删除账号', `确定删除 "${item.name}" 吗？`, [
            { text: '取消', style: 'cancel' },
            { text: '删除', style: 'destructive', onPress: () => removeAccount(id) },
          ]);
        }}
      />
    ),
    [loginLoadingId, handleLogin, setActiveAccount, handleEdit, removeAccount],
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
            <Text style={{ color: '#fff', fontSize: 20, fontWeight: '700', flex: 1 }}>KKBOX 账号管理</Text>
            <TouchableOpacity onPress={() => { if (editingId) { handleCancelAdd(); } else { setShowAdd(!showAdd); } }} activeOpacity={0.7} style={{ padding: 4 }}>
              <Ionicons name={showAdd ? "close" : "add"} size={24} color="#818cf8" />
            </TouchableOpacity>
          </View>

          {showAdd && (
            <View style={{ marginHorizontal: screenPadding.horizontal, backgroundColor: 'rgba(255,255,255,0.03)', borderRadius: 12, padding: 16, marginBottom: 12 }}>
              <TextInput value={newName} onChangeText={setNewName} placeholder="名称（如：主账号）" placeholderTextColor="rgba(255,255,255,0.3)"
                style={{ color: '#fff', fontSize: 15, backgroundColor: 'rgba(255,255,255,0.05)', borderRadius: 8, padding: 12, marginBottom: 8 }} />
              <TextInput value={newEmail} onChangeText={setNewEmail} placeholder="邮箱" placeholderTextColor="rgba(255,255,255,0.3)"
                autoCapitalize="none" keyboardType="email-address"
                style={{ color: '#fff', fontSize: 15, backgroundColor: 'rgba(255,255,255,0.05)', borderRadius: 8, padding: 12, marginBottom: 8 }} />
              <View style={{ flexDirection: 'row', alignItems: 'center', backgroundColor: 'rgba(255,255,255,0.05)', borderRadius: 8, marginBottom: 12 }}>
                <TextInput value={newPassword} onChangeText={setNewPassword} placeholder="密码" placeholderTextColor="rgba(255,255,255,0.3)"
                  secureTextEntry={!showPassword}
                  style={{ color: '#fff', fontSize: 15, flex: 1, padding: 12 }} />
                <TouchableOpacity onPress={() => setShowPassword(!showPassword)} activeOpacity={0.7} style={{ padding: 8, marginRight: 4 }}>
                  <Ionicons name={showPassword ? 'eye-off-outline' : 'eye-outline'} size={20} color="rgba(255,255,255,0.35)" />
                </TouchableOpacity>
              </View>
              <TouchableOpacity onPress={handleSave} activeOpacity={0.7}
                style={{ backgroundColor: '#818cf8', borderRadius: 8, paddingVertical: 10, alignItems: 'center' }}>
                <Text style={{ color: '#fff', fontSize: 15, fontWeight: '600' }}>{editingId ? '更新' : '保存'}</Text>
              </TouchableOpacity>
            </View>
          )}

          {accounts.length === 0 ? (
            <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center' }}>
              <Ionicons name="person-outline" size={48} color="rgba(255,255,255,0.06)" />
              <Text style={{ color: 'rgba(255,255,255,0.2)', fontSize: 16, marginTop: 12 }}>暂无账号，点击右上角添加</Text>
            </View>
          ) : (
            <FlatList
              data={accounts}
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

import React, { useState, useCallback } from 'react';
import { View, Text, TextInput, TouchableOpacity } from 'react-native';
import { Ionicons } from '@expo/vector-icons';

interface KkboxLinkFetchProps {
  onSubmit: (text: string) => void;
}

export const KkboxLinkFetch = React.memo(({ onSubmit }: KkboxLinkFetchProps) => {
  const [text, setText] = useState('');

  const handleSubmit = useCallback(() => {
    onSubmit(text.trim());
  }, [text, onSubmit]);

  return (
    <View style={{ flex: 1, alignItems: 'center', paddingHorizontal: 32, paddingTop: 60 }}>
      <Text style={{ color: 'rgba(255,255,255,0.6)', fontSize: 24, fontWeight: '700', marginBottom: 24 }}>
        KKBOX解析
      </Text>
      <View style={{
        flexDirection: 'row', alignItems: 'center',
        backgroundColor: 'rgba(255,255,255,0.06)', borderRadius: 14,
        width: '100%', height: 52,
      }}>
        <TextInput
          value={text}
          onChangeText={setText}
          placeholder="请粘贴 KKBOX 歌曲链接或者专辑链接"
          placeholderTextColor="rgba(255,255,255,0.3)"
          autoCapitalize="none"
          autoCorrect={false}
          onSubmitEditing={handleSubmit}
          returnKeyType="go"
          style={{
            flex: 1, color: '#fff', fontSize: 15,
            paddingHorizontal: 16, height: '100%',
          }}
        />
        {text.length > 0 && (
          <TouchableOpacity onPress={() => setText('')} activeOpacity={0.7} style={{ paddingHorizontal: 6, marginLeft: -10 }}>
            <Ionicons name="close-circle" size={18} color="rgba(255,255,255,0.2)" />
          </TouchableOpacity>
        )}
        <TouchableOpacity
          onPress={handleSubmit}
          activeOpacity={0.7}
          style={{
            width: 48, height: 48, borderRadius: 12,
            backgroundColor: '#818cf8', alignItems: 'center', justifyContent: 'center',
            marginRight: 2,
          }}
        >
          <Ionicons name="arrow-forward" size={20} color="#fff" />
        </TouchableOpacity>
      </View>
      <Text style={{ color: 'rgba(255,255,255,0.25)', fontSize: 12, textAlign: 'center', marginTop: 12 }}>
        请在设置页面配置账号再使用
      </Text>
    </View>
  );
});

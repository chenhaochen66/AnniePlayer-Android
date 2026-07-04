import React from 'react';
import {
  View,
  TextInput,
  TouchableOpacity,
  Platform,
} from 'react-native';
import { Ionicons } from '@expo/vector-icons';

interface SearchBarProps {
  query: string;
  onQueryChange: (text: string) => void;
  onSubmit: () => void;
}

export const SearchBar = React.memo(({ query, onQueryChange, onSubmit }: SearchBarProps) => {
  const isWeb = Platform.OS === 'web';

  return (
    <View style={{ paddingHorizontal: 16, paddingVertical: 8 }}>
      <View
        style={{
          flexDirection: 'row',
          alignItems: 'center',
          backgroundColor: 'rgba(255,255,255,0.05)',
          borderRadius: 10,
          paddingLeft: 10,
          height: 36,
        }}
      >
        <TextInput
          value={query}
          onChangeText={onQueryChange}
          onSubmitEditing={onSubmit}
          returnKeyType="search"
          placeholder="搜索歌曲、歌手..."
          placeholderTextColor="rgba(255,255,255,0.2)"
          style={{
            flex: 1,
            color: '#fff',
            fontSize: 14,
            fontFamily: isWeb ? "'Inter', sans-serif" : undefined,
            ...(isWeb ? ({ outlineStyle: 'none', outlineWidth: 0 } as any) : {}),
            paddingVertical: 0,
          }}
        />
        {query.length > 0 && (
          <TouchableOpacity onPress={() => onQueryChange('')} activeOpacity={0.7} style={{ paddingHorizontal: 6 }}>
            <Ionicons name="close-circle" size={15} color="rgba(255,255,255,0.15)" />
          </TouchableOpacity>
        )}
        <TouchableOpacity onPress={onSubmit} activeOpacity={0.7} style={{ width: 36, height: 36, alignItems: 'center', justifyContent: 'center' }}>
          <Ionicons name="search" size={17} color="rgba(255,255,255,0.5)" />
        </TouchableOpacity>
      </View>
    </View>
  );
});
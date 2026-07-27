import React from 'react';
import { View, Text, TouchableOpacity } from 'react-native';
import { Ionicons } from '@expo/vector-icons';

type SearchMode = 'song' | 'album' | 'singer' | 'qobuz';

interface ModeDropdownProps {
  mode: SearchMode;
  open: boolean;
  onToggle: () => void;
  onSelect: (mode: SearchMode) => void;
}

const OPTIONS: { key: SearchMode; label: string }[] = [
  { key: 'song', label: '单曲搜索' },
  { key: 'album', label: '专辑搜索' },
  { key: 'singer', label: '歌手搜索' },
  { key: 'qobuz', label: '国外解析' },
];

export const ModeDropdown = React.memo(({ mode, open, onToggle, onSelect }: ModeDropdownProps) => (
  <View>
    <TouchableOpacity
      onPress={onToggle}
      activeOpacity={0.7}
      style={{
        flexDirection: 'row', alignItems: 'center', alignSelf: 'flex-start',
        backgroundColor: '#818cf8',
        borderRadius: 10, height: 36, paddingHorizontal: 12, gap: 6,
      }}
    >
      <Ionicons name="grid-outline" size={15} color="rgba(255,255,255,0.8)" />
      <Text style={{ color: '#fff', fontSize: 13, fontWeight: '600' }}>
        {OPTIONS.find((o) => o.key === mode)?.label}
      </Text>
      <Ionicons name="chevron-down" size={13} color="rgba(255,255,255,0.7)" />
    </TouchableOpacity>

    {open && (
      <View style={{
        position: 'absolute', top: 40, left: 0,
        backgroundColor: '#1a1a2e', borderRadius: 12,
        paddingVertical: 4, paddingHorizontal: 4,
        borderWidth: 1, borderColor: 'rgba(255,255,255,0.06)',
        zIndex: 100, elevation: 10,
        minWidth: 140,
      }}>
        {OPTIONS.map((o) => {
          const active = mode === o.key;
          return (
            <TouchableOpacity
              key={o.key}
              onPress={() => onSelect(o.key)}
              activeOpacity={0.7}
              style={{
                flexDirection: 'row', alignItems: 'center',
                backgroundColor: active ? 'rgba(129,140,248,0.12)' : 'transparent',
                borderRadius: 8, paddingVertical: 10, paddingHorizontal: 12, gap: 8,
              }}
            >
              <Text style={{
                color: active ? '#fff' : 'rgba(255,255,255,0.6)',
                fontSize: 14, fontWeight: '500', flex: 1,
              }}>
                {o.label}
              </Text>
              {active && <Ionicons name="checkmark" size={16} color="#818cf8" />}
            </TouchableOpacity>
          );
        })}
      </View>
    )}
  </View>
));

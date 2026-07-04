import React from 'react';
import { View, Text, TouchableOpacity, Modal, Platform } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { Quality, QUALITY_LABELS } from '../types';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useUIStore } from '../store/useUIStore';

interface QualityPickerProps {
  visible?: boolean;
  qualities?: Quality[];
  onSelect: (quality: Quality) => void;
  onCancel?: () => void;
}

const QUALITY_DETAILS: Record<Quality, { icon: keyof typeof Ionicons.glyphMap; desc: string; color: string }> = {
  '128k': { icon: 'musical-note', desc: 'MP3 128kbps', color: 'rgba(255,255,255,0.5)' },
  '320k': { icon: 'musical-note', desc: 'MP3 320kbps', color: '#818cf8' },
  'flac': { icon: 'disc', desc: 'FLAC 无损', color: '#fbbf24' },
  'hires': { icon: 'disc', desc: 'Hi-Res 高解析', color: '#f472b6' },
};

const QUALITY_ORDER: Record<Quality, number> = { hires: 0, flac: 1, '320k': 2, '128k': 3 };

export const QualityPicker = React.memo(({ visible: v, qualities: q, onSelect, onCancel: oc }: QualityPickerProps) => {
  const { bottom } = useSafeAreaInsets();
  const storeVisible = useUIStore((s) => s.pickerVisible);
  const pickerSong = useUIStore((s) => s.pickerSong);
  const setPicker = useUIStore((s) => s.setPicker);
  const defaultCancel = React.useCallback(() => setPicker(false), [setPicker]);
  const onCancel = oc ?? defaultCancel;
  const visible = v ?? storeVisible;
  const qualities = q ?? (pickerSong?.qualities || ['128k']);
  const sortedQualities = React.useMemo(
    () => [...qualities].sort((a, b) => QUALITY_ORDER[a] - QUALITY_ORDER[b]),
    [qualities],
  );

  return (
    <Modal visible={visible} transparent animationType="fade" onRequestClose={onCancel}>
      <TouchableOpacity
        activeOpacity={1}
        onPress={onCancel}
        style={{ flex: 1, backgroundColor: 'rgba(0,0,0,0.6)', justifyContent: 'flex-end' }}
      >
        <TouchableOpacity activeOpacity={1} onPress={() => {}}>
          <View
            style={{
              backgroundColor: '#1a1a2e',
              borderTopLeftRadius: 20,
              borderTopRightRadius: 20,
              paddingTop: 20,
              paddingBottom: bottom + 16,
              paddingHorizontal: 20,
            }}
          >
            <View
              style={{
                width: 36, height: 4, borderRadius: 2,
                backgroundColor: 'rgba(255,255,255,0.15)',
                alignSelf: 'center', marginBottom: 20,
              }}
            />
            <Text style={{ color: '#fff', fontSize: 18, fontWeight: '700', marginBottom: 4 }}>
              选择音质
            </Text>
            <Text style={{ color: 'rgba(255,255,255,0.35)', fontSize: 13, marginBottom: 20 }}>
              请选择要下载的音质格式
            </Text>

            {sortedQualities.map((q) => {
              const detail = QUALITY_DETAILS[q];
              return (
                <TouchableOpacity
                  key={q}
                  onPress={() => onSelect(q)}
                  activeOpacity={0.7}
                  style={{
                    flexDirection: 'row',
                    alignItems: 'center',
                    backgroundColor: 'rgba(255,255,255,0.03)',
                    borderRadius: 12,
                    padding: 16,
                    marginBottom: 8,
                  }}
                >
                  <Ionicons name={detail.icon} size={22} color={detail.color} />
                  <View style={{ flex: 1, marginLeft: 14 }}>
                    <Text style={{ color: '#fff', fontSize: 16, fontWeight: '600' }}>
                      {QUALITY_LABELS[q]}
                    </Text>
                    <Text style={{ color: detail.color, fontSize: 12, marginTop: 1 }}>
                      {detail.desc}
                    </Text>
                  </View>
                  <Ionicons name="download-outline" size={18} color="rgba(255,255,255,0.25)" />
                </TouchableOpacity>
              );
            })}

            <TouchableOpacity
              onPress={onCancel}
              activeOpacity={0.7}
              style={{
                alignItems: 'center',
                paddingVertical: 14,
                marginTop: 4,
                borderRadius: 12,
                backgroundColor: 'rgba(255,255,255,0.05)',
              }}
            >
              <Text style={{ color: 'rgba(255,255,255,0.4)', fontSize: 15, fontWeight: '500' }}>
                取消
              </Text>
            </TouchableOpacity>
          </View>
        </TouchableOpacity>
      </TouchableOpacity>
    </Modal>
  );
});

import React from 'react';
import { TouchableOpacity, Text } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { useUIStore } from '../store/useUIStore';

export const QueueSection = React.memo(() => {
  const setVisible = useUIStore((s) => s.setQueueVisible);

  return (
    <TouchableOpacity onPress={() => setVisible(true)} activeOpacity={0.7} style={{ alignItems: 'center', gap: 2 }}>
      <Ionicons name="list" size={20} color="rgba(255,255,255,0.5)" />
      <Text style={{ color: 'rgba(255,255,255,0.2)', fontSize: 8, lineHeight: 8 }}>队列</Text>
    </TouchableOpacity>
  );
});

import React from 'react';
import { TouchableOpacity, Text } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { useUIStore } from '../store/useUIStore';

export const QueueSection = React.memo(() => {
  const setVisible = useUIStore((s) => s.setQueueVisible);

  return (
    <TouchableOpacity onPress={() => setVisible(true)} activeOpacity={0.7}>
      <Ionicons name="list" size={24} color="rgba(255,255,255,0.7)" />
    </TouchableOpacity>
  );
});

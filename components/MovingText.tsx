import React from 'react';
import { Text, View } from 'react-native';

interface MovingTextProps {
  text: string;
  style?: any;
  threshold?: number;
}

export const MovingText = React.memo(({ text, style }: MovingTextProps) => {
  if (!text) return null;
  return (
    <View style={{ flexDirection: 'row', flexShrink: 0 }}>
      <Text style={style} numberOfLines={1}>{text}</Text>
    </View>
  );
});

import React, { useState } from 'react';
import { View, TouchableOpacity, Text } from 'react-native';
import { QobuzLinkFetch } from './QobuzLinkFetch';
import { KkboxLinkFetch } from './KkboxLinkFetch';

type ForeignService = 'qobuz' | 'kkbox';

interface Props {
  onQobuzSubmit: (text: string) => void;
  onKkboxSubmit: (text: string) => void;
}

export const ForeignSearchView = React.memo(({ onQobuzSubmit, onKkboxSubmit }: Props) => {
  const [service, setService] = useState<ForeignService>('qobuz');

  return (
    <View style={{ flex: 1 }}>
      <View style={{ flexDirection: 'row', paddingHorizontal: 32, gap: 8, paddingTop: 12, paddingBottom: 4 }}>
        {(['qobuz', 'kkbox'] as const).map((s) => (
          <TouchableOpacity
            key={s}
            onPress={() => setService(s)}
            activeOpacity={0.7}
            style={{
              paddingHorizontal: 16, paddingVertical: 6, borderRadius: 8,
              backgroundColor: service === s ? '#818cf8' : 'rgba(255,255,255,0.05)',
            }}
          >
            <Text style={{ color: service === s ? '#fff' : 'rgba(255,255,255,0.5)', fontSize: 13, fontWeight: '600' }}>
              {s === 'qobuz' ? 'Qobuz' : 'KKBOX'}
            </Text>
          </TouchableOpacity>
        ))}
      </View>
      {service === 'qobuz' ? (
        <QobuzLinkFetch onSubmit={onQobuzSubmit} />
      ) : (
        <KkboxLinkFetch onSubmit={onKkboxSubmit} />
      )}
    </View>
  );
});

import React, { useState, useEffect } from 'react';
import { View, Text, TouchableOpacity, Dimensions, BackHandler, Image } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import Animated, {
  useAnimatedStyle,
  useSharedValue,
  withTiming,
} from 'react-native-reanimated';
import { useUIStore } from '../store/useUIStore';

const { width, height } = Dimensions.get('window');

export const ContactModal = React.memo(() => {
  const visible = useUIStore((s) => s.contactVisible);
  const setVisible = useUIStore((s) => s.setContactVisible);
  const [previewImage, setPreviewImage] = useState<any>(null);
  const opacity = useSharedValue(0);

  useEffect(() => {
    opacity.value = withTiming(visible ? 1 : 0, { duration: 200 });
  }, [visible]);

  useEffect(() => {
    if (!visible) return;
    const onBack = () => {
      if (previewImage) { setPreviewImage(null); return true; }
      setVisible(false); return true;
    };
    const sub = BackHandler.addEventListener('hardwareBackPress', onBack);
    return () => sub.remove();
  }, [visible, setVisible, previewImage]);

  const overlayStyle = useAnimatedStyle(() => ({
    opacity: opacity.value,
  }));

  if (!visible) return null;

  return (
    <Animated.View
      pointerEvents="auto"
      style={[
        {
          position: 'absolute',
          top: 0,
          left: 0,
          right: 0,
          bottom: 0,
          zIndex: 300,
          justifyContent: 'center',
          alignItems: 'center',
          backgroundColor: 'rgba(0,0,0,0.6)',
        },
        overlayStyle,
      ]}
    >
      <View
        style={{
          width: width * 0.88,
          backgroundColor: '#1e1e3a',
          borderRadius: 20,
          paddingVertical: 40,
          paddingHorizontal: 28,
          alignItems: 'center',
        }}
      >
        <Text style={{ color: '#fff', fontSize: 18, fontWeight: '700', marginBottom: 28, alignSelf: 'center' }}>
          联系我们
        </Text>

        <View style={{ width: '100%', gap: 16, marginBottom: 28 }}>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 12 }}>
            <TouchableOpacity onPress={() => setPreviewImage(require('../assets/group1.jpg'))} activeOpacity={0.7}>
              <Image
                source={require('../assets/group1.jpg')}
                style={{ width: 80, height: 80, borderRadius: 10, backgroundColor: 'rgba(255,255,255,0.06)' }}
                resizeMode="contain"
              />
            </TouchableOpacity>
            <Text style={{ color: 'rgba(255,255,255,0.7)', fontSize: 14, fontWeight: '500', flex: 1 }}>
              联系我们①
            </Text>
          </View>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 12 }}>
            <TouchableOpacity onPress={() => setPreviewImage(require('../assets/group2.jpg'))} activeOpacity={0.7}>
              <Image
                source={require('../assets/group2.jpg')}
                style={{ width: 80, height: 80, borderRadius: 10, backgroundColor: 'rgba(255,255,255,0.06)' }}
                resizeMode="contain"
              />
            </TouchableOpacity>
            <Text style={{ color: 'rgba(255,255,255,0.7)', fontSize: 14, fontWeight: '500', flex: 1 }}>
              联系我们②
            </Text>
          </View>
        </View>

        <TouchableOpacity
          onPress={() => setVisible(false)}
          activeOpacity={0.7}
          style={{
            backgroundColor: 'rgba(255,255,255,0.08)',
            borderRadius: 12,
            paddingHorizontal: 32,
            paddingVertical: 10,
          }}
        >
          <Text style={{ color: '#fff', fontSize: 15, fontWeight: '600' }}>关闭</Text>
        </TouchableOpacity>
        <Text style={{ color: 'rgba(255,255,255,0.3)', fontSize: 11, marginTop: 16, textAlign: 'center', lineHeight: 16 }}>
          声明：本软件仅供学习交流使用，完全免费，不涉及任何商业用途。所有资源请在下载后24小时内删除。
        </Text>
        <Text style={{ color: 'rgba(255,255,255,0.3)', fontSize: 11, marginTop: 6, textAlign: 'center', lineHeight: 16 }}>
          鸣谢：IKUN MUSIC提供的公益网易解析源
        </Text>
      </View>

      {previewImage && (
        <TouchableOpacity
          activeOpacity={1}
          onPress={() => setPreviewImage(null)}
          style={{
            position: 'absolute', top: 0, left: 0, right: 0, bottom: 0,
            backgroundColor: 'rgba(0,0,0,0.92)',
            justifyContent: 'center', alignItems: 'center',
          }}
        >
          <Image
            source={previewImage}
            style={{ width: width * 0.9, height: height * 0.7 }}
            resizeMode="contain"
          />
          <TouchableOpacity
            onPress={() => setPreviewImage(null)}
            activeOpacity={0.7}
            style={{ position: 'absolute', top: 50, right: 20, padding: 8 }}
          >
            <Ionicons name="close" size={28} color="#fff" />
          </TouchableOpacity>
        </TouchableOpacity>
      )}
    </Animated.View>
  );
});

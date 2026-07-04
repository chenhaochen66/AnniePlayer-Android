import { useEffect, useCallback } from 'react';
import { Platform, BackHandler } from 'react-native';
import { useRouter, usePathname } from 'expo-router';

export function useBackHandler() {
  const router = useRouter();
  const pathname = usePathname();
  const isWeb = Platform.OS === 'web';

  const handleBack = useCallback((): boolean => {
    if (pathname === '/player') {
      router.back();
      return true;
    }
    return false;
  }, [pathname, router]);

  useEffect(() => {
    if (isWeb) {
      const onPopState = () => {
        if (pathname === '/player') {
          router.back();
        }
      };
      window.addEventListener('popstate', onPopState);
      return () => {
        window.removeEventListener('popstate', onPopState);
      };
    }

    const subscription = BackHandler.addEventListener('hardwareBackPress', handleBack);
    return () => {
      subscription.remove();
    };
  }, [handleBack, isWeb, pathname, router]);
}

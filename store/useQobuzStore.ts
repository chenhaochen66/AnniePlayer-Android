import { create } from 'zustand';
import { persist, createJSONStorage } from 'zustand/middleware';
import AsyncStorage from '@react-native-async-storage/async-storage';
import type { QobuzToken } from '../types';

interface QobuzState {
  tokens: QobuzToken[];
  addToken: (name: string, token: string) => void;
  removeToken: (id: string) => void;
  setActiveToken: (id: string) => void;
  getActiveToken: () => string | undefined;
}

export const useQobuzStore = create<QobuzState>()(
  persist(
    (set, get) => ({
      tokens: [],
      addToken: (name, token) =>
        set((s) => ({
          tokens: [
            ...s.tokens,
            {
              id: Date.now().toString(36) + Math.random().toString(36).slice(2, 6),
              name,
              token,
              isActive: s.tokens.length === 0,
            },
          ],
        })),
      removeToken: (id) =>
        set((s) => {
          const removed = s.tokens.find((t) => t.id === id);
          const rest = s.tokens.filter((t) => t.id !== id);
          if (removed?.isActive && rest.length > 0) rest[0].isActive = true;
          return { tokens: rest };
        }),
      setActiveToken: (id) =>
        set((s) => ({
          tokens: s.tokens.map((t) => ({ ...t, isActive: t.id === id })),
        })),
      getActiveToken: () => get().tokens.find((t) => t.isActive)?.token,
    }),
    {
      name: 'qobuz-tokens',
      storage: createJSONStorage(() => AsyncStorage),
    },
  ),
);

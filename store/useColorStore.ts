import { create } from 'zustand';
import { persist, createJSONStorage } from 'zustand/middleware';
import AsyncStorage from '@react-native-async-storage/async-storage';
import type { ColorPalette } from '../types';

const defaultColors: ColorPalette = {
  primary: '#6366f1',
  secondary: '#312e81',
  background: '#0a0a1a',
  surface: '#1a1a2e',
};

interface ColorStore {
  colors: ColorPalette;
  currentCoverUrl: string;
  setColors: (colors: Partial<ColorPalette>) => void;
  setCurrentCoverUrl: (url: string) => void;
}

export const useColorStore = create<ColorStore>()(
  persist(
    (set) => ({
      colors: defaultColors,
      currentCoverUrl: '',

      setColors: (partial) =>
        set((state) => ({
          colors: { ...state.colors, ...partial },
        })),

      setCurrentCoverUrl: (url) => set({ currentCoverUrl: url }),
    }),
    {
      name: 'color-storage',
      storage: createJSONStorage(() => AsyncStorage),
      partialize: (state) => ({ colors: state.colors }),
    },
  ),
);

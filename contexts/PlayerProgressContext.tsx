import React, { createContext, useContext } from 'react';
import { useSharedValue, type SharedValue } from 'react-native-reanimated';

interface ProgressCtx {
  position: SharedValue<number>;
  duration: SharedValue<number>;
}

const Ctx = createContext<ProgressCtx | null>(null);

export function PlayerProgressProvider({ children }: { children: React.ReactNode }) {
  const position = useSharedValue(0);
  const duration = useSharedValue(0);
  return <Ctx.Provider value={{ position, duration }}>{children}</Ctx.Provider>;
}

export function usePlayerProgress(): ProgressCtx {
  const ctx = useContext(Ctx);
  if (!ctx) throw new Error('usePlayerProgress must be inside PlayerProgressProvider');
  return ctx;
}

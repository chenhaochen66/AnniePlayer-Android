import { create } from 'zustand';
import { persist, createJSONStorage } from 'zustand/middleware';
import AsyncStorage from '@react-native-async-storage/async-storage';
import type { KkboxAccount, KkboxSession } from '../types';

interface KkboxState {
  accounts: KkboxAccount[];
  addAccount: (name: string, email: string, password: string, session: KkboxSession) => void;
  updateAccount: (id: string, name: string, email: string, password: string) => void;
  removeAccount: (id: string) => void;
  setActiveAccount: (id: string) => void;
  updateAccountSession: (id: string, session: KkboxSession) => void;
  clearAccountSession: (id: string) => void;
  getActiveAccount: () => KkboxAccount | undefined;
}

export const useKkboxStore = create<KkboxState>()(
  persist(
    (set, get) => ({
      accounts: [],
      addAccount: (name, email, password, session) =>
        set((s) => ({
          accounts: [
            ...s.accounts,
            {
              id: Date.now().toString(36) + Math.random().toString(36).slice(2, 6),
              name, email, password, session,
              isActive: s.accounts.length === 0,
            },
          ],
        })),
      updateAccount: (id, name, email, password) =>
        set((s) => ({
          accounts: s.accounts.map((a) => (a.id === id ? { ...a, name, email, password } : a)),
        })),
      removeAccount: (id) =>
        set((s) => {
          const rest = s.accounts.filter((a) => a.id !== id);
          const removed = s.accounts.find((a) => a.id === id);
          if (removed?.isActive && rest.length > 0) {
            return { accounts: [{ ...rest[0], isActive: true }, ...rest.slice(1)] };
          }
          return { accounts: rest };
        }),
      setActiveAccount: (id) =>
        set((s) => ({
          accounts: s.accounts.map((a) => ({ ...a, isActive: a.id === id })),
        })),
      updateAccountSession: (id, session) =>
        set((s) => ({
          accounts: s.accounts.map((a) => (a.id === id ? { ...a, session } : a)),
        })),
      clearAccountSession: (id) =>
        set((s) => ({
          accounts: s.accounts.map((a) => (a.id === id ? { ...a, session: null } : a)),
        })),
      getActiveAccount: () => get().accounts.find((a) => a.isActive),
    }),
    {
      name: 'kkbox-accounts',
      storage: createJSONStorage(() => AsyncStorage),
    },
  ),
);

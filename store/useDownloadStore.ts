import { create } from 'zustand';
import type { Song, Quality } from '../types';

interface DownloadTaskState {
  songId: string;
  title: string;
  artist: string;
  cover: string;
  quality: Quality;
  progress: number;
  status: 'pending' | 'downloading' | 'completed' | 'error';
  filePath?: string;
}

interface DownloadStore {
  tasks: Record<string, DownloadTaskState>;
  addTask: (song: Song, quality: Quality) => void;
  updateProgress: (songId: string, progress: number) => void;
  completeTask: (songId: string, filePath?: string) => void;
  failTask: (songId: string) => void;
  removeTask: (songId: string) => void;
  clearCompleted: () => void;
}

export const useDownloadStore = create<DownloadStore>()(
  (set, get) => ({
    tasks: {},

    addTask: (song, quality) => {
      const { tasks } = get();
      if (tasks[song.id]) return;
      set({
        tasks: {
          ...tasks,
          [song.id]: {
            songId: song.id,
            title: song.title,
            artist: song.artist,
            cover: song.cover,
            quality,
            progress: 0,
            status: 'pending',
          },
        },
      });
    },

    updateProgress: (songId, progress) => {
      const { tasks } = get();
      const task = tasks[songId];
      if (!task) return;
      set({
        tasks: {
          ...tasks,
          [songId]: { ...task, progress, status: progress < 100 ? 'downloading' : 'completed' },
        },
      });
    },

    completeTask: (songId, filePath) => {
      const { tasks } = get();
      const task = tasks[songId];
      if (!task) return;
      set({
        tasks: {
          ...tasks,
          [songId]: { ...task, progress: 100, status: 'completed', filePath },
        },
      });
    },

    failTask: (songId) => {
      const { tasks } = get();
      const task = tasks[songId];
      if (!task) return;
      set({
        tasks: {
          ...tasks,
          [songId]: { ...task, status: 'error' },
        },
      });
    },

    removeTask: (songId) => {
      const { tasks } = get();
      const newTasks = { ...tasks };
      delete newTasks[songId];
      set({ tasks: newTasks });
    },

    clearCompleted: () => {
      const { tasks } = get();
      const newTasks: Record<string, DownloadTaskState> = {};
      for (const [id, task] of Object.entries(tasks)) {
        if (task.status !== 'completed') {
          newTasks[id] = task;
        }
      }
      set({ tasks: newTasks });
    },
  }),
);

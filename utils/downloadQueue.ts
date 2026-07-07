type QueueTask = () => Promise<void>;

const taskMap = new Map<string, QueueTask>();
let currentPromise: Promise<void> = Promise.resolve();

export function enqueueDownload(songId: string, task: QueueTask): void {
  taskMap.set(songId, task);
  const run = async () => {
    const fn = taskMap.get(songId);
    if (!fn) return;
    taskMap.delete(songId);
    await fn();
  };
  currentPromise = currentPromise.then(run, run);
}

export function cancelPending(songId: string): void {
  taskMap.delete(songId);
}

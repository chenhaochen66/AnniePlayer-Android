type QueueTask = () => Promise<void>;

let currentPromise: Promise<void> = Promise.resolve();

export function enqueueDownload(task: QueueTask): void {
  currentPromise = currentPromise.then(task, task);
}

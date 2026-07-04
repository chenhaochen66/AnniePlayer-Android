export async function retryUntilSuccess<T>(
  fn: () => Promise<T>,
  isValid: (result: T) => boolean,
  maxAttempts = 10,
  delayMs = 1000,
): Promise<T> {
  for (let i = 0; i < maxAttempts; i++) {
    try {
      const result = await fn();
      if (isValid(result)) return result;
    } catch {}
    if (i < maxAttempts - 1) await new Promise((r) => setTimeout(r, delayMs));
  }
  throw new Error('retryUntilSuccess: failed after ' + maxAttempts + ' attempts');
}

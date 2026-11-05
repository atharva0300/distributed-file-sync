import axios from 'axios';

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

/**
 * Runs `fn`, retrying on HTTP 429 (honouring Retry-After) and transient 502/503/504 / network
 * errors with capped exponential backoff plus jitter. Other errors propagate immediately.
 */
export async function withRetry<T>(fn: () => Promise<T>, maxAttempts = 6): Promise<T> {
  for (let attempt = 1; ; attempt++) {
    try {
      return await fn();
    } catch (e) {
      if (attempt >= maxAttempts || !axios.isAxiosError(e)) throw e;
      const status = e.response?.status;
      const transient = status === undefined || status === 502 || status === 503 || status === 504;
      if (status !== 429 && !transient) throw e;
      const retryAfter = Number(e.response?.headers?.['retry-after']);
      const backoff = Math.min(30_000, 500 * 2 ** (attempt - 1));
      const delay = status === 429 && retryAfter > 0 ? retryAfter * 1000 : backoff;
      await sleep(delay + Math.random() * 250);
    }
  }
}

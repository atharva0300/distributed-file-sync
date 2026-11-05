import { describe, expect, it, vi } from 'vitest';
import { AxiosError, AxiosHeaders } from 'axios';
import { withRetry } from './retry';

function httpError(status: number, headers: Record<string, string> = {}) {
  return new AxiosError('x', 'ERR', undefined, undefined, {
    status, statusText: '', data: {}, config: { headers: new AxiosHeaders() } as never,
    headers,
  });
}

describe('withRetry', () => {
  it('retries 429 using Retry-After then succeeds', async () => {
    vi.useFakeTimers();
    const fn = vi.fn().mockRejectedValueOnce(httpError(429, { 'retry-after': '2' })).mockResolvedValue('ok');
    const p = withRetry(fn);
    await vi.advanceTimersByTimeAsync(2000);
    await vi.advanceTimersByTimeAsync(300);
    await expect(p).resolves.toBe('ok');
    expect(fn).toHaveBeenCalledTimes(2);
    vi.useRealTimers();
  });

  it('does not retry client errors', async () => {
    const fn = vi.fn().mockRejectedValue(httpError(400));
    await expect(withRetry(fn)).rejects.toBeDefined();
    expect(fn).toHaveBeenCalledTimes(1);
  });

  it('gives up after max attempts', async () => {
    vi.useFakeTimers();
    const fn = vi.fn().mockRejectedValue(httpError(503));
    const p = withRetry(fn, 3);
    const assertion = expect(p).rejects.toBeDefined();
    await vi.advanceTimersByTimeAsync(60_000);
    await assertion;
    expect(fn).toHaveBeenCalledTimes(3);
    vi.useRealTimers();
  });
});

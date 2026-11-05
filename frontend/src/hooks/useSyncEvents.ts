import { useEffect, useRef } from 'react';

export interface SyncEvent {
  eventType: string;
  userId: number;
  fileId: number;
  fileName: string;
  version: number;
  message: string;
  timestamp: string;
}

/**
 * Incremental parser for a text/event-stream body. Feed decoded text pieces; it invokes
 * `onMessage(eventName, data)` for each complete event (blank-line terminated).
 */
export function createSseParser(onMessage: (event: string, data: string) => void) {
  let buffer = '';
  let event = 'message';
  let data: string[] = [];
  return (chunk: string) => {
    buffer += chunk;
    let nl: number;
    while ((nl = buffer.search(/\r\n|\n|\r/)) !== -1) {
      const line = buffer.slice(0, nl);
      buffer = buffer.slice(nl + (buffer.startsWith('\r\n', nl) ? 2 : 1));
      if (line === '') {
        if (data.length) onMessage(event, data.join('\n'));
        event = 'message';
        data = [];
      } else if (line.startsWith(':')) {
        // comment / keep-alive
      } else {
        const i = line.indexOf(':');
        const field = i === -1 ? line : line.slice(0, i);
        const value = i === -1 ? '' : line.slice(i + 1).replace(/^ /, '');
        if (field === 'event') event = value;
        else if (field === 'data') data.push(value);
      }
    }
  };
}

// One-directional push over Server-Sent Events: the server says "something changed, re-fetch via
// REST". EventSource cannot send an Authorization header, and putting the JWT in the URL leaks it
// into proxy/access logs and browser history, so the stream is read with fetch() instead.
export function useSyncEvents(userId: number | null, onEvent: (event: SyncEvent) => void) {
  const onEventRef = useRef(onEvent);
  onEventRef.current = onEvent;

  useEffect(() => {
    if (!userId) return;
    const abort = new AbortController();

    const run = async () => {
      for (let attempt = 0; !abort.signal.aborted; attempt++) {
        try {
          const token = localStorage.getItem('token') || '';
          const res = await fetch('/api/sync/events', {
            headers: { Authorization: `Bearer ${token}`, Accept: 'text/event-stream' },
            signal: abort.signal,
          });
          if (res.status === 401) return; // token invalid: the API client's 401 handling logs out
          if (!res.ok || !res.body) throw new Error(`SSE ${res.status}`);
          attempt = 0;
          const parse = createSseParser((name, data) => {
            if (name !== 'sync') return;
            try {
              onEventRef.current(JSON.parse(data) as SyncEvent);
            } catch (err) {
              console.error('Failed to parse SSE event', err);
            }
          });
          const reader = res.body.pipeThrough(new TextDecoderStream()).getReader();
          for (;;) {
            const { done, value } = await reader.read();
            if (done) break;
            parse(value);
          }
        } catch {
          if (abort.signal.aborted) return;
        }
        const delay = Math.min(30_000, 1000 * 2 ** Math.min(attempt, 5));
        await new Promise((r) => setTimeout(r, delay + Math.random() * 500));
      }
    };
    void run();
    return () => abort.abort();
  }, [userId]);
}

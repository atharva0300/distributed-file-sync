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

// One-directional push notifications over Server-Sent Events: the server tells
// connected clients "something changed, go re-fetch via REST". The client never
// sends anything back over this connection, which is why SSE (plain HTTP,
// auto-reconnecting, one-way) replaces what was previously a full WebSocket.
export function useSyncEvents(
  userId: number | null,
  onEvent: (event: SyncEvent) => void
) {
  const onEventRef = useRef(onEvent);
  onEventRef.current = onEvent;

  useEffect(() => {
    if (!userId) return;

    // EventSource can't set an Authorization header, so the token travels as a
    // query param on this one endpoint (file-sync-service scopes accepting it
    // there to this exact path).
    const token = localStorage.getItem('token') || '';
    const source = new EventSource(`/api/sync/events?token=${encodeURIComponent(token)}`);

    source.addEventListener('sync', (e: MessageEvent) => {
      try {
        const event: SyncEvent = JSON.parse(e.data);
        onEventRef.current(event);
      } catch (err) {
        console.error('Failed to parse SSE event', err);
      }
    });

    source.onerror = () => {
      // EventSource reconnects automatically on transient errors; nothing to do here.
    };

    return () => {
      source.close();
    };
  }, [userId]);
}

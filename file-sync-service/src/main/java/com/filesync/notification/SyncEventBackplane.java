package com.filesync.notification;

/** Optional cross-instance transport for sync events (Redis pub/sub). */
public interface SyncEventBackplane {
    void publish(SyncEvent event);
}

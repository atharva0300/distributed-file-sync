package com.filesync.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Delivers {@link SyncEvent}s only after the publishing transaction has committed, so clients
 * never get told about a change that was rolled back (or that is not yet visible). Delivery is
 * best-effort: a failure here can neither undo the committed change nor fail the request, and
 * clients recover missed hints by re-fetching on reconnect.
 */
@Slf4j
@Component
public class SyncEventDispatcher {

    private final SyncNotificationService notificationService;
    private final SyncEventBackplane backplane;

    public SyncEventDispatcher(SyncNotificationService notificationService,
                               org.springframework.beans.factory.ObjectProvider<SyncEventBackplane> backplane) {
        this.notificationService = notificationService;
        this.backplane = backplane.getIfAvailable();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommitted(SyncEvent event) {
        try {
            if (backplane != null) {
                backplane.publish(event);
            } else {
                notificationService.broadcast(event);
            }
        } catch (Exception e) {
            log.error("SYNC_EVENT_DELIVERY_FAILED: type={}, userId={}", event.eventType(), event.userId(), e);
        }
    }
}

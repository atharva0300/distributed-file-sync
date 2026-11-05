package com.filesync.notification;

import com.filesync.FileSyncApplication;
import com.filesync.auth.entity.User;
import com.filesync.auth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Sync events are delivered after commit only, and delivery failures cannot affect the committed data. */
@SpringBootTest(classes = FileSyncApplication.class)
@ActiveProfiles("test")
class SyncEventDispatchTest {

    @Autowired ApplicationEventPublisher publisher;
    @Autowired TransactionTemplate tx;
    @Autowired UserRepository userRepository;

    @MockBean SyncNotificationService notificationService;

    private SyncEvent event() {
        return SyncEvent.fileUpdated(1L, 2L, "a.txt", 3);
    }

    @Test
    void eventIsNotDeliveredBeforeCommitButIsDeliveredAfter() {
        tx.executeWithoutResult(s -> {
            publisher.publishEvent(event());
            verify(notificationService, never()).broadcast(any());
        });
        verify(notificationService, times(1)).broadcast(any(SyncEvent.class));
    }

    @Test
    void rolledBackTransactionDeliversNothing() {
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(s -> {
            publisher.publishEvent(event());
            throw new IllegalStateException("boom");
        }));
        verify(notificationService, never()).broadcast(any());
    }

    @Test
    void deliveryFailureDoesNotRollBackOrFailTheCommittedTransaction() {
        doThrow(new RuntimeException("sse down")).when(notificationService).broadcast(any());
        String email = "evt-" + UUID.randomUUID() + "@example.com";

        assertDoesNotThrow(() -> tx.executeWithoutResult(s -> {
            userRepository.save(new User(email, "pw"));
            publisher.publishEvent(event());
        }));

        assertTrue(userRepository.findByEmail(email).isPresent(), "data must stay committed");
        verify(notificationService).broadcast(any(SyncEvent.class));
    }
}

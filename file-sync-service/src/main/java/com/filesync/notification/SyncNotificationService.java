package com.filesync.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Push notification service: fans a file/sync event out to every browser tab or
 * device the user currently has connected, via Server-Sent Events.
 *
 * <p>The client never needs to send anything back over this channel — it only
 * learns "something changed, go re-fetch via REST" — so SSE (one-way,
 * plain-HTTP, auto-reconnecting) replaces what was previously a full
 * WebSocket/STOMP connection used in only one direction.
 *
 * <p>With a single instance (the default) events are delivered directly by
 * {@link SyncEventDispatcher}. With {@code app.notifications.backplane=redis} the dispatcher
 * publishes to Redis instead and {@code onMessage} is invoked on every instance, so a user's SSE
 * connection is served regardless of which instance committed the change.
 *
 * <p>Binary data is NEVER sent over this channel — only notification metadata.
 */
@Slf4j
@Service
public class SyncNotificationService implements MessageListener {

    private final ObjectMapper objectMapper;

    // One user can have several emitters (multiple tabs/devices connected at once).
    private final Map<Long, List<SseEmitter>> emittersByUserId = new ConcurrentHashMap<>();

    public SyncNotificationService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public SseEmitter subscribe(Long userId) {
        SseEmitter emitter = new SseEmitter(0L); // no timeout; client reconnects on drop
        emittersByUserId.computeIfAbsent(userId, id -> new CopyOnWriteArrayList<>()).add(emitter);

        Runnable cleanup = () -> {
            List<SseEmitter> emitters = emittersByUserId.get(userId);
            if (emitters != null) {
                emitters.remove(emitter);
            }
        };
        emitter.onCompletion(cleanup::run);
        emitter.onTimeout(cleanup::run);
        emitter.onError(ex -> cleanup.run());

        try {
            emitter.send(SseEmitter.event().name("connected").data("ok"));
        } catch (IOException e) {
            cleanup.run();
        }
        return emitter;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            SyncEvent event = objectMapper.readValue(body, SyncEvent.class);
            broadcast(event);
        } catch (Exception e) {
            log.error("Failed to process Redis message", e);
        }
    }

    /** Pushes the event to every SSE connection of its user on this instance. */
    public void broadcast(SyncEvent event) {
        Long userId = event.userId();
        List<SseEmitter> emitters = emittersByUserId.get(userId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("sync").data(event));
            } catch (IOException | IllegalStateException e) {
                emitters.remove(emitter);
            }
        }
        log.debug("SSE event sent to user {}: {}", userId, event.eventType());
    }
}

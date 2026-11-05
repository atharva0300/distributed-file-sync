package com.filesync.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filesync.notification.SyncEvent;
import com.filesync.notification.SyncEventBackplane;
import com.filesync.notification.SyncNotificationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis pub/sub backplane for sync events. Only active with
 * {@code app.notifications.backplane=redis}; the default single-instance deployment needs no Redis.
 */
@Configuration
@ConditionalOnProperty(name = "app.notifications.backplane", havingValue = "redis")
public class RedisConfig {

    public static final String SYNC_EVENTS_TOPIC = "sync-events";

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            SyncNotificationService syncNotificationService) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(syncNotificationService, new ChannelTopic(SYNC_EVENTS_TOPIC));
        return container;
    }

    @Bean
    public SyncEventBackplane redisSyncEventBackplane(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        return (SyncEvent event) -> {
            try {
                redisTemplate.convertAndSend(SYNC_EVENTS_TOPIC, objectMapper.writeValueAsString(event));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException("Could not serialise sync event", e);
            }
        };
    }
}

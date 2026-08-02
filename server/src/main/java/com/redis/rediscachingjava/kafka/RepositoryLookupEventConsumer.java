package com.redis.rediscachingjava.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.redis.rediscachingjava.cache.LookupLeaderboard;

@Component
public class RepositoryLookupEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(RepositoryLookupEventConsumer.class);

    private final LookupLeaderboard leaderboard;

    public RepositoryLookupEventConsumer(LookupLeaderboard leaderboard) {
        this.leaderboard = leaderboard;
    }

    @KafkaListener(topics = "repository-lookups", groupId = "repository-lookup-consumer")
    public void onEvent(RepositoryLookupEvent event) {
        log.info("Received repository lookup event: {}", event);
        leaderboard.recordLookup(event.username());
    }
}

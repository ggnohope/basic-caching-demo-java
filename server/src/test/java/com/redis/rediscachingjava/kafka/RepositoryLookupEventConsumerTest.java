package com.redis.rediscachingjava.kafka;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.redis.rediscachingjava.cache.LookupLeaderboard;

class RepositoryLookupEventConsumerTest {

    @Test
    void recordsLookupInLeaderboardWhenEventReceived() {
        LookupLeaderboard leaderboard = mock(LookupLeaderboard.class);
        RepositoryLookupEventConsumer consumer = new RepositoryLookupEventConsumer(leaderboard);
        RepositoryLookupEvent event =
                new RepositoryLookupEvent("octocat", "8", true, Instant.parse("2026-08-02T00:00:00Z"));

        consumer.onEvent(event);

        verify(leaderboard).recordLookup("octocat");
    }
}

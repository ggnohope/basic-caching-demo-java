package com.redis.rediscachingjava.kafka;

import java.time.Instant;

public record RepositoryLookupEvent(String username, String repos, boolean cached, Instant timestamp) {
}

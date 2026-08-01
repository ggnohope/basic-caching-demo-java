package com.redis.rediscachingjava.cache;

import java.time.Duration;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RepositoryCountCache {

    private final StringRedisTemplate redisTemplate;
    private final Duration ttl;

    public RepositoryCountCache(
            StringRedisTemplate redisTemplate,
            @Value("${app.cache.repository-ttl}") Duration ttl) {
        this.redisTemplate = redisTemplate;
        this.ttl = ttl;
    }

    public Optional<String> get(String username) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(username));
    }

    public void put(String username, String repoCount) {
        redisTemplate.opsForValue().set(username, repoCount, ttl);
    }
}

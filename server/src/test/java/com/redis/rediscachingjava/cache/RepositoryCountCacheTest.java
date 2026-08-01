package com.redis.rediscachingjava.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RepositoryCountCacheTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private RepositoryCountCache cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        cache = new RepositoryCountCache(redisTemplate, Duration.ofHours(1));
    }

    @Test
    void getReturnsEmptyWhenKeyMissing() {
        when(valueOperations.get("octocat")).thenReturn(null);

        assertThat(cache.get("octocat")).isEmpty();
    }

    @Test
    void getReturnsValueWhenKeyPresent() {
        when(valueOperations.get("octocat")).thenReturn("8");

        assertThat(cache.get("octocat")).contains("8");
    }

    @Test
    void putStoresValueWithConfiguredTtl() {
        cache.put("octocat", "8");

        verify(valueOperations).set(eq("octocat"), eq("8"), eq(Duration.ofHours(1)));
    }
}

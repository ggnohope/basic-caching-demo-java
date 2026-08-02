package com.redis.rediscachingjava.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import com.redis.rediscachingjava.dto.LeaderboardEntry;

@SuppressWarnings("unchecked")
class LookupLeaderboardTest {

    private static final String KEY = "repository-lookup-counts";

    private StringRedisTemplate redisTemplate;
    private ZSetOperations<String, String> zSetOperations;
    private LookupLeaderboard leaderboard;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        zSetOperations = mock(ZSetOperations.class);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        leaderboard = new LookupLeaderboard(redisTemplate);
    }

    @Test
    void recordLookupIncrementsScoreByOne() {
        leaderboard.recordLookup("octocat");

        verify(zSetOperations).incrementScore(KEY, "octocat", 1);
    }

    @Test
    void topEntriesReturnsOrderedListFromRedis() {
        Set<ZSetOperations.TypedTuple<String>> tuples = new LinkedHashSet<>();
        tuples.add(ZSetOperations.TypedTuple.of("torvalds", 3.0));
        tuples.add(ZSetOperations.TypedTuple.of("octocat", 1.0));
        when(zSetOperations.reverseRangeWithScores(KEY, 0, 9)).thenReturn(tuples);

        List<LeaderboardEntry> result = leaderboard.topEntries(10);

        assertThat(result).containsExactly(
                new LeaderboardEntry("torvalds", 3),
                new LeaderboardEntry("octocat", 1));
    }

    @Test
    void topEntriesReturnsEmptyListWhenRedisReturnsNull() {
        when(zSetOperations.reverseRangeWithScores(KEY, 0, 9)).thenReturn(null);

        List<LeaderboardEntry> result = leaderboard.topEntries(10);

        assertThat(result).isEmpty();
    }
}

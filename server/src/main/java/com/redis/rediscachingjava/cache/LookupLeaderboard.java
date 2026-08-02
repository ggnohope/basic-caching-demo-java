package com.redis.rediscachingjava.cache;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import com.redis.rediscachingjava.dto.LeaderboardEntry;

@Component
public class LookupLeaderboard {

    private static final String KEY = "repository-lookup-counts";

    private final StringRedisTemplate redisTemplate;

    public LookupLeaderboard(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void recordLookup(String username) {
        redisTemplate.opsForZSet().incrementScore(KEY, username, 1);
    }

    public List<LeaderboardEntry> topEntries(int limit) {
        Set<ZSetOperations.TypedTuple<String>> tuples =
                redisTemplate.opsForZSet().reverseRangeWithScores(KEY, 0, limit - 1);

        List<LeaderboardEntry> entries = new ArrayList<>();
        if (tuples != null) {
            for (ZSetOperations.TypedTuple<String> tuple : tuples) {
                entries.add(new LeaderboardEntry(tuple.getValue(), tuple.getScore().longValue()));
            }
        }
        return entries;
    }
}

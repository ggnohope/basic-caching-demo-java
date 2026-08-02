package com.redis.rediscachingjava.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.redis.rediscachingjava.cache.LookupLeaderboard;
import com.redis.rediscachingjava.dto.LeaderboardEntry;

@RestController
public class LeaderboardController {

    private static final int MAX_LIMIT = 100;

    private final LookupLeaderboard leaderboard;

    public LeaderboardController(LookupLeaderboard leaderboard) {
        this.leaderboard = leaderboard;
    }

    @GetMapping("/stats/top")
    public List<LeaderboardEntry> getTopLookups(@RequestParam(defaultValue = "10") int limit) {
        int clampedLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
        return leaderboard.topEntries(clampedLimit);
    }
}

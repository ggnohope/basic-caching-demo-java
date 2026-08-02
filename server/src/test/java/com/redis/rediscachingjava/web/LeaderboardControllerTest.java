package com.redis.rediscachingjava.web;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.redis.rediscachingjava.cache.LookupLeaderboard;
import com.redis.rediscachingjava.config.SecurityConfig;
import com.redis.rediscachingjava.dto.LeaderboardEntry;

@WebMvcTest(LeaderboardController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "app.cors.allowed-origins=*")
class LeaderboardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private LookupLeaderboard leaderboard;

    @Test
    void returnsTopEntriesAsJsonArray() throws Exception {
        when(leaderboard.topEntries(10)).thenReturn(List.of(
                new LeaderboardEntry("torvalds", 3),
                new LeaderboardEntry("octocat", 1)));

        mockMvc.perform(get("/stats/top"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].username").value("torvalds"))
                .andExpect(jsonPath("$[0].count").value(3))
                .andExpect(jsonPath("$[1].username").value("octocat"))
                .andExpect(jsonPath("$[1].count").value(1));
    }

    @Test
    void defaultsLimitToTenWhenNotProvided() throws Exception {
        when(leaderboard.topEntries(10)).thenReturn(List.of());

        mockMvc.perform(get("/stats/top")).andExpect(status().isOk());

        verify(leaderboard).topEntries(10);
    }

    @Test
    void passesThroughExplicitLimit() throws Exception {
        when(leaderboard.topEntries(5)).thenReturn(List.of());

        mockMvc.perform(get("/stats/top?limit=5")).andExpect(status().isOk());

        verify(leaderboard).topEntries(5);
    }

    @Test
    void clampsLimitAboveMaxTo100() throws Exception {
        when(leaderboard.topEntries(100)).thenReturn(List.of());

        mockMvc.perform(get("/stats/top?limit=500")).andExpect(status().isOk());

        verify(leaderboard).topEntries(100);
    }
}

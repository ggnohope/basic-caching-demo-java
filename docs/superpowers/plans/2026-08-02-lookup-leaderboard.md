# Lookup Leaderboard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The Kafka consumer does real work beyond logging — it aggregates lookup counts per username into a Redis sorted set, exposed via a new `GET /stats/top` endpoint. Demonstrable event-driven materialized view: call `/repos/{username}` a few times, then check `/stats/top`.

**Architecture:** New `LookupLeaderboard` (Redis ZSET wrapper) in the existing `cache/` package, new `LeaderboardEntry` DTO, new `LeaderboardController` (separate from `RepositoryController`), and a one-line addition to the existing `RepositoryLookupEventConsumer`. Full design rationale: `docs/superpowers/specs/2026-08-02-lookup-leaderboard-design.md`.

**Tech Stack:** Spring Data Redis (`StringRedisTemplate.opsForZSet()`, already in use), JUnit 5, Mockito, AssertJ, MockMvc — no new dependencies.

---

## Contract reminder (do not break)

`/repos/{username}` and `/repos/{username}`'s response shape are completely unchanged. This plan is purely additive: one new Redis key (`repository-lookup-counts`), one new endpoint (`GET /stats/top`), one new call inside the existing consumer.

---

### Task 1: `LeaderboardEntry` and `LookupLeaderboard`

**Files:**
- Create: `server/src/main/java/com/redis/rediscachingjava/dto/LeaderboardEntry.java`
- Create: `server/src/main/java/com/redis/rediscachingjava/cache/LookupLeaderboard.java`
- Test: `server/src/test/java/com/redis/rediscachingjava/cache/LookupLeaderboardTest.java`

- [ ] **Step 1: Create the DTO**

```java
package com.redis.rediscachingjava.dto;

public record LeaderboardEntry(String username, long count) {
}
```

- [ ] **Step 2: Write the failing test**

```java
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
```

- [ ] **Step 3: Run the test to verify it fails**

Run:
```bash
./gradlew server:test --tests "com.redis.rediscachingjava.cache.LookupLeaderboardTest"
```
Expected: FAIL to compile — `LookupLeaderboard` does not exist yet (RED state).

- [ ] **Step 4: Implement `LookupLeaderboard`**

```java
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
```

`ZINCRBY`/`ZREVRANGE ... WITHSCORES` are the standard Redis leaderboard
commands — Redis itself returns results pre-sorted by score, so no
application-side sorting is needed; `topEntries` just maps the already-
ordered result.

- [ ] **Step 5: Run the test to verify it passes**

Run:
```bash
./gradlew server:test --tests "com.redis.rediscachingjava.cache.LookupLeaderboardTest"
```
Expected: `BUILD SUCCESSFUL`, 3 tests passed.

- [ ] **Step 6: Commit**

```bash
git add server/src/main/java/com/redis/rediscachingjava/dto/LeaderboardEntry.java \
        server/src/main/java/com/redis/rediscachingjava/cache/LookupLeaderboard.java \
        server/src/test/java/com/redis/rediscachingjava/cache/LookupLeaderboardTest.java
git commit -m "feat: add LookupLeaderboard backed by a Redis sorted set"
```

## Context for Task 1

This is Task 1 of 4 (full plan and design: `docs/superpowers/plans/2026-08-02-lookup-leaderboard.md` (this file), `docs/superpowers/specs/2026-08-02-lookup-leaderboard-design.md`). `StringRedisTemplate` is already a Spring-managed bean in this app (configured by the existing `RedisConfig`, from the prior server refactor) — `LookupLeaderboard` just injects it, same pattern as the existing `RepositoryCountCache`. `LookupLeaderboard` will be consumed by `RepositoryLookupEventConsumer` (Task 2, not yours) and `LeaderboardController` (Task 3, not yours).

---

### Task 2: Wire the leaderboard into `RepositoryLookupEventConsumer`

**Files:**
- Modify: `server/src/main/java/com/redis/rediscachingjava/kafka/RepositoryLookupEventConsumer.java`
- Create: `server/src/test/java/com/redis/rediscachingjava/kafka/RepositoryLookupEventConsumerTest.java`

The consumer previously had no dedicated test (it was a single log
statement with no branching logic). It now calls a collaborator, so it
crosses into "worth testing" territory — same reasoning applied to every
other class with real behavior in this codebase.

- [ ] **Step 1: Write the failing test**

```java
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
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
./gradlew server:test --tests "com.redis.rediscachingjava.kafka.RepositoryLookupEventConsumerTest"
```
Expected: FAIL to compile — `RepositoryLookupEventConsumer`'s constructor
doesn't accept a `LookupLeaderboard` yet (RED state).

- [ ] **Step 3: Update `RepositoryLookupEventConsumer`**

Replace the full contents of
`server/src/main/java/com/redis/rediscachingjava/kafka/RepositoryLookupEventConsumer.java`
with:

```java
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
```

The log line stays — it's still useful to see the raw event separately
from the aggregate it feeds into.

- [ ] **Step 4: Run the test to verify it passes**

Run:
```bash
./gradlew server:test --tests "com.redis.rediscachingjava.kafka.RepositoryLookupEventConsumerTest"
```
Expected: `BUILD SUCCESSFUL`, 1 test passed.

- [ ] **Step 5: Run the full test suite**

Run:
```bash
./gradlew server:test
```
Expected: `BUILD SUCCESSFUL`, no regressions (23 existing + 3 from Task 1 +
1 from this task = 27 so far; Task 3 adds more).

- [ ] **Step 6: Commit**

```bash
git add server/src/main/java/com/redis/rediscachingjava/kafka/RepositoryLookupEventConsumer.java \
        server/src/test/java/com/redis/rediscachingjava/kafka/RepositoryLookupEventConsumerTest.java
git commit -m "feat: record lookups in the leaderboard from RepositoryLookupEventConsumer"
```

## Context for Task 2

This is Task 2 of 4. Task 1 (not yours to redo — assume it's done) created
`LookupLeaderboard`. This task is the write-side wiring: every event the
consumer already receives now also increments that user's leaderboard
score.

---

### Task 3: `LeaderboardController`

**Files:**
- Create: `server/src/main/java/com/redis/rediscachingjava/web/LeaderboardController.java`
- Test: `server/src/test/java/com/redis/rediscachingjava/web/LeaderboardControllerTest.java`

- [ ] **Step 1: Write the failing tests**

```java
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
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:
```bash
./gradlew server:test --tests "com.redis.rediscachingjava.web.LeaderboardControllerTest"
```
Expected: FAIL to compile — `LeaderboardController` does not exist yet
(RED state).

- [ ] **Step 3: Implement `LeaderboardController`**

```java
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
```

No security/CORS config changes are needed — `SecurityConfig` (from the
prior refactor) already applies `permitAll()` + GET-only CORS to
`anyRequest()`, which automatically covers this new `GET` route.

- [ ] **Step 4: Run the tests to verify they pass**

Run:
```bash
./gradlew server:test --tests "com.redis.rediscachingjava.web.LeaderboardControllerTest"
```
Expected: `BUILD SUCCESSFUL`, 4 tests passed.

- [ ] **Step 5: Run the full test suite**

Run:
```bash
./gradlew server:test
```
Expected: `BUILD SUCCESSFUL`, all tests pass (27 from Tasks 1-2 + 4 from
this task = 31 total).

- [ ] **Step 6: Commit**

```bash
git add server/src/main/java/com/redis/rediscachingjava/web/LeaderboardController.java \
        server/src/test/java/com/redis/rediscachingjava/web/LeaderboardControllerTest.java
git commit -m "feat: add GET /stats/top leaderboard endpoint"
```

## Context for Task 3

This is Task 3 of 4. Tasks 1-2 (not yours to redo — assume done) built the
write side (`LookupLeaderboard.recordLookup`, called from the consumer).
This task adds the read side: a new, separate controller (not added to the
existing `RepositoryController`, which stays focused on the single-user
lookup endpoint) exposing `GET /stats/top?limit=N`.

---

### Task 4: End-to-end verification against the real stack

This closes the loop: everything up to here is unit-tested with mocks.
This task proves the real, wired-up feature works against a real Redis and
a real Kafka broker — the same way the earlier Kafka feature's Task 6 was
verified.

**Files:** none (verification only).

- [ ] **Step 1: Ensure the full stack is running**

Run:
```bash
docker compose up -d
docker compose ps
```
Expected: `caching-demo-redis` and `caching-demo-kafka` both `healthy`;
`caching-demo-redisinsight` and `caching-demo-kafka-ui` both `Up`.

- [ ] **Step 2: Clear any leftover leaderboard state from prior manual testing**

Run:
```bash
docker exec caching-demo-redis redis-cli DEL repository-lookup-counts
```

- [ ] **Step 3: Build and start the server**

Run:
```bash
./gradlew server:build
./gradlew server:bootRun
```
Wait until the log shows `Started RedisCachingJavaApplication`.

- [ ] **Step 4: Generate some lookups**

Run:
```bash
curl -s http://localhost:8080/repos/torvalds > /dev/null
curl -s http://localhost:8080/repos/torvalds > /dev/null
curl -s http://localhost:8080/repos/torvalds > /dev/null
curl -s http://localhost:8080/repos/octocat > /dev/null
```

- [ ] **Step 5: Confirm the leaderboard reflects them**

Run:
```bash
curl -s http://localhost:8080/stats/top
```
Expected: JSON array with `torvalds` first (`"count":3`), `octocat` second
(`"count":1`).

- [ ] **Step 6: Cross-check against raw Redis state**

Run:
```bash
docker exec caching-demo-redis redis-cli ZREVRANGE repository-lookup-counts 0 -1 WITHSCORES
```
Expected: `torvalds`, `3`, `octocat`, `1` — matching the endpoint's
response exactly, proving the endpoint reads the same data the consumer
wrote.

- [ ] **Step 7: Verify the `limit` parameter**

Run:
```bash
curl -s "http://localhost:8080/stats/top?limit=1"
```
Expected: JSON array with exactly one entry, `torvalds`.

- [ ] **Step 8: Confirm `/repos/{username}` itself is unaffected**

Run:
```bash
curl -s -D - http://localhost:8080/repos/torvalds
```
Expected: identical response shape/headers to before this feature —
`200`, `X-Response-Time` header ending in `ms`, JSON body with
`username`/`repos`/`cached` fields only (no leaderboard data leaking into
this response).

- [ ] **Step 9: Stop the server**

Run:
```bash
pkill -9 -f RedisCachingJavaApplication
```

No commit for this task — it's verification only, nothing to check in.

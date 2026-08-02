# Lookup Leaderboard (Kafka Consumer Handling) — Design

Date: 2026-08-02
Scope: `server/` only. `client/` (Vue) is out of scope and unchanged.

## Problem

The Kafka event-log feature (already implemented) publishes a
`RepositoryLookupEvent` on every successful `/repos/{username}` lookup, but
`RepositoryLookupEventConsumer` only logs the event — there's no way to
*see* the consumer doing meaningful work beyond reading a log line. The goal
is a small, genuinely useful piece of consumer-side processing that's easy
to demonstrate: call the lookup endpoint a few times, then see the effect.

## Goal

The consumer maintains a leaderboard of "most looked-up GitHub usernames"
in Redis, built entirely from the Kafka event stream (not from the
request-handling path directly) — a small, real example of the classic
event-driven "materialized view" pattern: the read model (`GET /stats/top`)
is derived asynchronously from an event stream, decoupled from the write
path (`GET /repos/{username}`).

## Non-goals

- No change to `/repos/{username}`'s behavior, response shape, or the
  `RepositoryLookupEvent` schema.
- No time-windowing, decay, or "trending" logic — a simple cumulative
  count per username, forever (until the Redis key is cleared).
- No pagination beyond a `limit` query parameter.
- No client (Vue) changes.
- No new external dependency — reuses the existing `StringRedisTemplate`
  bean (already configured via `RedisConfig` from the prior refactor).

## Architecture

```
com.redis.rediscachingjava
├── cache/
│   └── LookupLeaderboard.java       — Redis ZSET wrapper: recordLookup, topEntries
├── dto/
│   └── LeaderboardEntry.java        — record: username, count
├── web/
│   └── LeaderboardController.java   — GET /stats/top?limit=10
└── kafka/
    └── RepositoryLookupEventConsumer.java (modified) — calls LookupLeaderboard.recordLookup
```

`RepositoryLookupEventConsumer` gains one constructor-injected collaborator,
`LookupLeaderboard`, and calls `recordLookup(event.username())` in addition
to its existing log line (the log line stays — still useful to see the raw
event, separate from the aggregate it feeds into).

`LeaderboardController` is a new, separate controller (not added to the
existing `RepositoryController`) — it serves a different concern (querying
an aggregate built from the event stream, not looking up a single GitHub
user), and keeping it separate matches the single-responsibility-per-class
pattern already established by every other class in this codebase.

## Data model

Redis key `repository-lookup-counts`, a sorted set (`ZSET`): member =
username, score = cumulative lookup count. This is the standard, idiomatic
Redis leaderboard structure — `ZINCRBY` for writes, `ZREVRANGE ...
WITHSCORES` for reads — chosen over a plain hash specifically because Redis
returns sorted-set reads pre-sorted by score, with no application-side
sorting needed.

Every event increments the count by 1, regardless of the event's `cached`
flag — "most looked-up" counts all lookups, hit or miss, matching what a
user intuitively expects from that phrase.

## Data flow

1. `GET /repos/{username}` (unchanged) → `RepositoryController` publishes a
   `RepositoryLookupEvent` as before.
2. `RepositoryLookupEventConsumer.onEvent` (already exists) receives it,
   logs it (unchanged), and now additionally calls
   `LookupLeaderboard.recordLookup(event.username())`.
3. `LookupLeaderboard.recordLookup` issues `ZINCRBY repository-lookup-counts
   1 <username>` via `StringRedisTemplate.opsForZSet().incrementScore(...)`.
4. `GET /stats/top?limit=N` (new) → `LeaderboardController` calls
   `LookupLeaderboard.topEntries(limit)`, which issues `ZREVRANGE
   repository-lookup-counts 0 N-1 WITHSCORES` via
   `opsForZSet().reverseRangeWithScores(...)`, maps the result to a list of
   `LeaderboardEntry(username, count)`, already sorted descending by count
   (Redis does the sorting, not application code).
5. Response: `200` with JSON array
   `[{"username":"torvalds","count":3}, ...]`.

`limit` defaults to 10 if not supplied; clamped to `[1, 100]` to prevent an
accidental huge query (no error thrown for an out-of-range value — silently
clamped, since this is a read-only, non-destructive query parameter on a
demo endpoint, not worth the ceremony of a 400 response).

## New endpoint contract

`GET /stats/top` and `GET /stats/top?limit=<n>` — no auth (matches the
existing `SecurityConfig`'s `permitAll()` + GET-only CORS, which already
covers any new `GET` route with zero config changes needed). Response:
`200`, `Content-Type: application/json`, body = JSON array of
`{"username": "<string>", "count": <integer>}`, sorted descending by
`count`. Empty array (not an error) if no lookups have happened yet.

This is a genuinely new, additive endpoint — it does not touch, wrap, or
alias `/repos/{username}` in any way.

## Testing

- `LookupLeaderboardTest` — mocks `StringRedisTemplate`/`ZSetOperations`.
  Verifies `recordLookup` calls `incrementScore(KEY, username, 1.0)`, and
  `topEntries(limit)` calls `reverseRangeWithScores(KEY, 0, limit - 1)` and
  correctly maps the returned `Set<ZSetOperations.TypedTuple<String>>` into
  an ordered `List<LeaderboardEntry>`.
- `RepositoryLookupEventConsumerTest` — **new** (the consumer previously had
  no dedicated test, per the earlier Kafka feature's design decision that a
  single log statement isn't worth testing; now that it has real behavior —
  calling a collaborator — it crosses into "worth testing" territory, same
  reasoning applied throughout this codebase). Mocks `LookupLeaderboard`,
  verifies `onEvent` calls `recordLookup` with the event's username.
- `LeaderboardControllerTest` — `@WebMvcTest`, mocks `LookupLeaderboard`,
  verifies the JSON array shape and that the `limit` query parameter is
  passed through (default 10 when omitted).

No Testcontainers / embedded Kafka or Redis — mocked collaborators only,
consistent with every other test in this codebase. Real end-to-end
behavior (actual `ZINCRBY`/`ZREVRANGE` against a live Redis, actual event
flowing through the real Kafka broker) is verified manually after
implementation, the same way the original Kafka feature was.

## Manual verification (not automated)

1. `docker compose up -d` (Redis + Kafka already defined).
2. `./gradlew server:bootRun`.
3. `curl http://localhost:8080/repos/torvalds` three times, `curl
   http://localhost:8080/repos/octocat` once.
4. `curl http://localhost:8080/stats/top` — expect `torvalds` first with
   `count: 3`, `octocat` second with `count: 1` (assuming a clean Redis
   state — if not, counts accumulate from prior manual testing, which is
   expected/fine for a learning demo).
5. `docker exec caching-demo-redis redis-cli ZREVRANGE
   repository-lookup-counts 0 -1 WITHSCORES` — confirm the raw Redis state
   matches the endpoint's response.

## Out of scope for this spec (explicitly not doing)

- Resetting/clearing the leaderboard (no `DELETE /stats/top` endpoint).
- Per-time-window leaderboards (daily/weekly top lists).
- Combining this with the existing `RepositoryCountCache` TTL'd cache in
  any way — they are two independent Redis keys serving two independent
  purposes.
- Client (Vue) changes.

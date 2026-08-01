# Kafka Event Log for Repository Lookups — Design

Date: 2026-08-01
Scope: `server/` only. `client/` (Vue) is untouched — this feature has no visible
effect on the HTTP contract the client depends on.

## Goal

Learning-oriented feature: every successful `GET /repos/{username}` lookup
publishes an event to Kafka. A consumer in the same app reads it and logs it.
This is a self-contained addition to demonstrate producer/consumer/
serialization basics with Spring Kafka, layered onto the existing
cache-lookup architecture from the prior refactor without changing it.

## Non-goals

- No change to the `/repos/{username}` response shape, status codes, or
  headers — the Kafka publish is a side effect, invisible to the client.
- No cache-invalidation-via-Kafka, no separate consumer microservice, no
  persisted analytics store (Redis list, DB) — explicitly deferred; this is
  the "publish + log" starting point only.
- No Kafka Streams, no multi-partition/multi-broker cluster — single
  broker, single partition, dev-only setup.
- No Testcontainers / embedded-Kafka integration tests — mocked unit tests
  only, consistent with the prior refactor's testing decision.

## Contract preservation

`GET /repos/{username}` behavior is unchanged in every observable way:
same JSON body, same `X-Response-Time` header, same status codes. The Kafka
publish call must never cause the endpoint to fail, slow down materially, or
change its response — see "Resilience" below.

## Architecture

```
com.redis.rediscachingjava
├── kafka/
│   ├── RepositoryLookupEvent.java         — record: username, repos, cached, timestamp
│   ├── RepositoryLookupEventProducer.java — wraps KafkaTemplate, publishes the event
│   └── RepositoryLookupEventConsumer.java — @KafkaListener, logs the event via SLF4J
└── config/
    └── KafkaTopicConfig.java              — @Bean NewTopic (topic auto-created on broker)
```

`RepositoryController` (existing, from the prior refactor) gains one new
constructor-injected collaborator, `RepositoryLookupEventProducer`, and calls
it once, after obtaining a successful `RepositoryCountResponse` from
`RepositoryLookupService` and before returning. `RepositoryLookupService`
itself is untouched — publishing is an HTTP-layer side effect, not part of
the cache/GitHub orchestration domain logic.

A lookup that ends in `GitHubUserNotFoundException` (404) or any other error
never reaches the publish call, since it's placed after the successful
service call returns — this is intentional: the event represents a
successful lookup, not a failed one.

## Data flow

1. `RepositoryController.getRepositoryCount` calls
   `RepositoryLookupService.getRepositoryCount(username)` as today.
2. On success, it builds `RepositoryLookupEvent(username, repos, cached,
   Instant.now())` and calls
   `RepositoryLookupEventProducer.publish(event)`.
3. `RepositoryLookupEventProducer` sends the event via `KafkaTemplate<String,
   RepositoryLookupEvent>` to the `repository-lookups` topic, keyed by
   `username` (so all events for the same user land on the same partition —
   irrelevant at 1 partition today, but the right habit for when partition
   count grows). Send is fire-and-forget (`KafkaTemplate.send` returns a
   `CompletableFuture`, not awaited), with a `whenComplete` callback that
   logs success/failure — never blocks or throws into the controller.
4. `RepositoryLookupEventConsumer.onEvent` (`@KafkaListener` on the same
   topic, its own consumer group `repository-lookup-consumer`) receives the
   event and logs it at INFO via SLF4J. Nothing else happens to it.
5. Controller returns the HTTP response to the client exactly as before,
   independent of whether the Kafka publish succeeded.

## Resilience

The Kafka publish must never break the main request path. Concretely:

- `RepositoryLookupEventProducer.publish` wraps the `KafkaTemplate.send`
  call in a try/catch. Any synchronous exception (e.g. a serialization
  failure, or certain broker-unreachable conditions that throw immediately
  rather than failing the returned future) is caught and logged at WARN,
  never rethrown.
- The async failure path (`CompletableFuture` completing exceptionally,
  e.g. broker unreachable, topic missing) is also just logged at WARN via
  the `whenComplete` callback — never surfaced to the caller.
- Net effect: if Kafka is down or misconfigured, `/repos/{username}` keeps
  working exactly as it did before this feature existed. This mirrors the
  existing app's philosophy (from the prior refactor) that a cache/side
  concern going down shouldn't take the primary read path down with it.

## Docker Compose additions

Two new services, alongside the existing `redis` / `redisinsight`:

- `kafka` — official Apache Kafka image, KRaft mode (combined
  controller+broker, single node, no Zookeeper — standard for Kafka 3.x+
  and simpler for local dev), port `9092` exposed for the app.
- `kafka-ui` — `provectuslabs/kafka-ui`, a web UI for browsing topics,
  messages, and consumer groups, connected to the `kafka` service, mapped
  to host port `8090` (app is on `8080`, RedisInsight is on `5540`). Serves
  the same role for Kafka that RedisInsight already serves for Redis in
  this repo — a visual aid for learning, not required for the app to run.

## Config (`application.yml`)

New top-level `spring.kafka` block, additive to the existing config (no
changes to `spring.data.redis`, `app.*`, `server.*`):

```yaml
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
    consumer:
      group-id: repository-lookup-consumer
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: com.redis.rediscachingjava.kafka
```

`KAFKA_BOOTSTRAP_SERVERS` defaults to `localhost:9092`, matching the
existing pattern of every other external dependency in this app (Redis,
GitHub API base URL) being env-var-configurable with a sane local default.

Topic `repository-lookups`: 1 partition, replication factor 1 — dev-only
values, declared via a `NewTopic` bean so Spring Boot's `KafkaAdmin`
auto-creates it against the broker on startup (no manual topic creation
step needed to run the app locally).

## Testing

- `RepositoryLookupEventProducerTest` — mocks `KafkaTemplate`, verifies
  `.send(topic, username, event)` is called with the correct topic, key,
  and event payload on a successful build. Also verifies the try/catch
  resilience behavior: if the mocked `KafkaTemplate.send` throws
  synchronously, `publish` does not propagate the exception.
- `RepositoryControllerTest` (existing, from the prior refactor) gains one
  additional test: `RepositoryLookupEventProducer` is `@MockBean`'d, and a
  test confirms the controller calls `publish` with the expected event
  after a successful lookup, and confirms the endpoint still returns 200 with
  the correct body when the mocked producer's `publish` call happens to
  throw (proving the resilience contract at the HTTP layer, not just inside
  the producer).
- No dedicated test for `RepositoryLookupEventConsumer` — it's a single log
  statement with no branching logic, consistent with this codebase's
  existing practice of not writing tests for trivial passthrough code (see
  the exception classes and DTO record from the prior refactor, which
  likewise have no dedicated behavior tests beyond what's already covered).
- No Testcontainers / embedded Kafka broker in the test suite — matches the
  prior refactor's explicit decision to keep the test suite fast and
  Docker-free, verified instead by manually running against the real
  `docker compose` Kafka broker (see "Manual verification" below).

## Manual verification (not automated)

After implementation, verify by hand against the real `docker compose`
stack:
1. `docker compose up -d` (brings up `redis`, `redisinsight`, `kafka`,
   `kafka-ui`).
2. `curl http://localhost:8080/repos/octocat` — confirm response unchanged
   from before this feature.
3. Open `kafka-ui` at `http://localhost:8090` (mapped there specifically to
   avoid clashing with the app's own `8080` and RedisInsight's `5540`) and
   confirm the `repository-lookups` topic has one new message.
4. Check the application log for the consumer's INFO log line for that
   event.
5. Stop the `kafka` container and repeat step 2 — confirm the endpoint
   still returns 200 normally (resilience check).

## Out of scope for this spec (explicitly not doing)

- Cache invalidation via Kafka.
- A separate consumer microservice/process.
- Persisting events anywhere beyond the log line (no Redis list, no DB).
- Kafka Streams, multi-partition topics, consumer scaling.
- Client (Vue) changes.

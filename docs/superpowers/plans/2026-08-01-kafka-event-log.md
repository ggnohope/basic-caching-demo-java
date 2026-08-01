# Kafka Event Log Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every successful `GET /repos/{username}` lookup publishes an event to Kafka; a consumer in the same app reads and logs it. Learning-focused addition to the existing layered Spring Boot 3 app, with zero change to the HTTP contract.

**Architecture:** New `kafka/` package (`RepositoryLookupEvent` record, `RepositoryLookupEventProducer`, `RepositoryLookupEventConsumer`) plus `config/KafkaTopicConfig`. `RepositoryController` gains one new collaborator and publishes after each successful lookup, wrapped so a Kafka failure never breaks the HTTP response. `docker-compose.yml` gains a KRaft-mode Kafka broker and a `kafka-ui` viewer. Full design rationale: `docs/superpowers/specs/2026-08-01-kafka-event-log-design.md`.

**Tech Stack:** Spring Kafka (version managed by the Spring Boot 3.3.5 BOM already in use), Apache Kafka (`apache/kafka` official image, KRaft mode), `provectuslabs/kafka-ui`, JUnit 5, Mockito, AssertJ.

---

## Contract reminder (do not break)

`GET /repos/{username}` response shape, status codes, and headers are unchanged — see `docs/superpowers/specs/2026-08-01-kafka-event-log-design.md`'s "Contract preservation" section. The Kafka publish is a side effect that must **never** cause the endpoint to fail or change its response, even if Kafka is completely unreachable.

---

### Task 1: Add Spring Kafka dependency and `spring.kafka` config

**Files:**
- Modify: `server/build.gradle`
- Modify: `server/src/main/resources/application.yml`

- [ ] **Step 1: Add the `spring-kafka` dependency**

In `server/build.gradle`, inside the `dependencies { }` block, add one line after the existing `spring-boot-starter-data-redis` line:

```groovy
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-security'
    implementation 'org.springframework.boot:spring-boot-starter-data-redis'
    implementation 'org.springframework.kafka:spring-kafka'
    developmentOnly 'org.springframework.boot:spring-boot-devtools'
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
}
```

No version is specified — it's managed by the Spring Boot 3.3.5 dependency
BOM already active via the `io.spring.dependency-management` plugin, same
as every other Spring Boot starter in this file.

- [ ] **Step 2: Add `spring.kafka` config to `application.yml`**

The file currently has no top-level `spring:` key at all (the Redis
auto-config block was deliberately removed in the prior refactor, replaced
by a hand-built `RedisConfig` bean — see the comment already in the file).
Add a new `spring:` block for Kafka only, right after the `server:` block
and its trailing comment, before the `app:` block:

```yaml
server:
  port: 8080
  error:
    include-stacktrace: never
    include-message: never

# spring.data.redis.* is intentionally NOT set here. Spring Boot 3.3.x's Redis
# auto-configuration fails to start when spring.data.redis.url resolves to an
# empty string (as it would via ${REDIS_URL:} when REDIS_URL is unset) — it
# does not gracefully fall back to host/port the way the old hand-rolled
# Jedis code did. RedisConfig.java builds the LettuceConnectionFactory
# manually instead, replicating the same REDIS_URL-wins-if-non-empty fallback
# using @Value("${REDIS_URL:}") directly, which Spring's property resolver
# handles fine outside of the Redis auto-configuration's URL-parsing path.

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

app:
  github:
    base-url: ${GITHUB_API_BASE_URL:https://api.github.com}
  cache:
    repository-ttl: ${REPO_CACHE_TTL:PT1H}
  cors:
    allowed-origins: ${CORS_ALLOWED_ORIGINS:*}
```

- [ ] **Step 3: Verify the module still builds**

Run (from repo root; Java 17 is required — see the `org.gradle.java.home`
setting already in the root `gradle.properties` from the prior refactor, no
extra JAVA_HOME setup should be needed):

```bash
./gradlew server:build
```

Expected: `BUILD SUCCESSFUL`. Nothing references Kafka classes yet, so this
only proves the new dependency resolves and the YAML is well-formed.

- [ ] **Step 4: Commit**

```bash
git add server/build.gradle server/src/main/resources/application.yml
git commit -m "build: add spring-kafka dependency and spring.kafka config"
```

---

### Task 2: Add Kafka and Kafka UI to `docker-compose.yml`

**Files:**
- Modify: `docker-compose.yml`

The app runs on the host (via `./gradlew server:bootRun`), not inside
Docker, while `kafka-ui` runs inside the Compose network. Kafka's client
protocol requires the broker to hand back an "advertised listener" address
on first connect, and that address must be reachable from wherever the
client actually is — so the broker needs **two different listeners**: one
advertised as `localhost:9092` for the host-side app, and one advertised as
`kafka:29092` for other containers (`kafka-ui`) on the Compose network. This
is the standard, well-known pattern for this exact setup (a Kafka broker
shared between host processes and other containers).

- [ ] **Step 1: Rewrite `docker-compose.yml`**

```yaml
services:
  redis:
    image: redis:7-alpine
    container_name: caching-demo-redis
    # App reads REDIS_PORT=6379 (server/src/main/resources/application.properties).
    ports:
      - "6379:6379"
    volumes:
      - redis-data:/data
    command: ["redis-server", "--appendonly", "yes"]
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 5

  redisinsight:
    image: redis/redisinsight:latest
    container_name: caching-demo-redisinsight
    ports:
      - "5540:5540"
    volumes:
      - redisinsight-data:/data
    depends_on:
      redis:
        condition: service_healthy

  kafka:
    image: apache/kafka:latest
    container_name: caching-demo-kafka
    ports:
      - "9092:9092"
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_LISTENERS: "CONTROLLER://:29093,HOST://:9092,DOCKER://:29092"
      KAFKA_ADVERTISED_LISTENERS: "HOST://localhost:9092,DOCKER://kafka:29092"
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: "CONTROLLER:PLAINTEXT,HOST:PLAINTEXT,DOCKER:PLAINTEXT"
      KAFKA_CONTROLLER_LISTENER_NAMES: "CONTROLLER"
      KAFKA_CONTROLLER_QUORUM_VOTERS: "1@kafka:29093"
      KAFKA_INTER_BROKER_LISTENER_NAME: "DOCKER"
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS: 0
      CLUSTER_ID: "4L6g3nShT-eMCtK--X86sw"
    healthcheck:
      test: ["CMD-SHELL", "/opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 10

  kafka-ui:
    image: provectuslabs/kafka-ui:latest
    container_name: caching-demo-kafka-ui
    ports:
      - "8090:8080"
    environment:
      KAFKA_CLUSTERS_0_NAME: local
      KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS: kafka:29092
    depends_on:
      kafka:
        condition: service_healthy

volumes:
  redis-data:
  redisinsight-data:
```

`CLUSTER_ID` is an arbitrary, valid-format (22-character URL-safe base64)
KRaft cluster identifier — this exact value is a widely-used, known-working
placeholder from Apache Kafka's own Docker documentation/examples, not a
real/meaningful ID. Any correctly-formatted string works; this one is used
here simply because it's proven to work.

- [ ] **Step 2: Start the new services and verify Kafka becomes healthy**

Run:
```bash
docker compose up -d kafka kafka-ui
docker compose ps
```

Expected: `caching-demo-kafka` reaches `healthy` within ~30-60s (KRaft
startup takes a few seconds longer than Redis). `caching-demo-kafka-ui`
shows `Up` (it has no healthcheck itself).

If `caching-demo-kafka` does not become healthy, run
`docker compose logs kafka` and read the actual error — do not proceed to
later tasks with a broken broker, since Task 6's end-to-end verification
depends on it.

- [ ] **Step 3: Verify kafka-ui can see the broker**

Run:
```bash
curl -s http://localhost:8090/api/clusters | head -c 500
```

Expected: JSON containing `"name":"local"` and a status field indicating
the cluster is online (not an error/empty response). This proves the
`DOCKER://kafka:29092` internal listener works — the trickiest part of the
listener config above.

- [ ] **Step 4: Commit**

```bash
git add docker-compose.yml
git commit -m "feat: add Kafka (KRaft mode) and kafka-ui to docker-compose"
```

---

### Task 3: `RepositoryLookupEvent`, `KafkaTopicConfig`, and `RepositoryLookupEventProducer`

**Files:**
- Create: `server/src/main/java/com/redis/rediscachingjava/kafka/RepositoryLookupEvent.java`
- Create: `server/src/main/java/com/redis/rediscachingjava/config/KafkaTopicConfig.java`
- Create: `server/src/main/java/com/redis/rediscachingjava/kafka/RepositoryLookupEventProducer.java`
- Test: `server/src/test/java/com/redis/rediscachingjava/kafka/RepositoryLookupEventProducerTest.java`

- [ ] **Step 1: Create the event record**

```java
package com.redis.rediscachingjava.kafka;

import java.time.Instant;

public record RepositoryLookupEvent(String username, String repos, boolean cached, Instant timestamp) {
}
```

- [ ] **Step 2: Create the topic config**

```java
package com.redis.rediscachingjava.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic repositoryLookupsTopic() {
        return TopicBuilder.name("repository-lookups")
                .partitions(1)
                .replicas(1)
                .build();
    }
}
```

Spring Boot's `KafkaAdmin` auto-configuration picks up this `NewTopic` bean
and creates the topic against the broker on application startup — no
manual topic-creation step is needed to run the app locally.

- [ ] **Step 3: Write the failing test for the producer**

```java
package com.redis.rediscachingjava.kafka;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

@SuppressWarnings("unchecked")
class RepositoryLookupEventProducerTest {

    private KafkaTemplate<String, RepositoryLookupEvent> kafkaTemplate;
    private RepositoryLookupEventProducer producer;

    @BeforeEach
    void setUp() {
        kafkaTemplate = mock(KafkaTemplate.class);
        producer = new RepositoryLookupEventProducer(kafkaTemplate);
    }

    @Test
    void sendsEventToRepositoryLookupsTopicKeyedByUsername() {
        RepositoryLookupEvent event =
                new RepositoryLookupEvent("octocat", "8", true, Instant.parse("2026-08-01T00:00:00Z"));
        SendResult<String, RepositoryLookupEvent> sendResult = mock(SendResult.class);
        when(kafkaTemplate.send("repository-lookups", "octocat", event))
                .thenReturn(CompletableFuture.completedFuture(sendResult));

        producer.publish(event);

        verify(kafkaTemplate).send("repository-lookups", "octocat", event);
    }

    @Test
    void doesNotPropagateWhenKafkaTemplateThrowsSynchronously() {
        RepositoryLookupEvent event =
                new RepositoryLookupEvent("octocat", "8", true, Instant.parse("2026-08-01T00:00:00Z"));
        when(kafkaTemplate.send("repository-lookups", "octocat", event))
                .thenThrow(new RuntimeException("boom"));

        assertThatCode(() -> producer.publish(event)).doesNotThrowAnyException();
    }

    @Test
    void doesNotPropagateWhenFutureCompletesExceptionally() {
        RepositoryLookupEvent event =
                new RepositoryLookupEvent("octocat", "8", true, Instant.parse("2026-08-01T00:00:00Z"));
        CompletableFuture<SendResult<String, RepositoryLookupEvent>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("broker down"));
        when(kafkaTemplate.send("repository-lookups", "octocat", event)).thenReturn(failedFuture);

        assertThatCode(() -> producer.publish(event)).doesNotThrowAnyException();
    }
}
```

- [ ] **Step 4: Run the test to verify it fails**

Run:
```bash
./gradlew server:test --tests "com.redis.rediscachingjava.kafka.RepositoryLookupEventProducerTest"
```
Expected: FAIL to compile — `RepositoryLookupEventProducer` does not exist
yet (RED state).

- [ ] **Step 5: Implement `RepositoryLookupEventProducer`**

```java
package com.redis.rediscachingjava.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class RepositoryLookupEventProducer {

    private static final String TOPIC = "repository-lookups";

    private static final Logger log = LoggerFactory.getLogger(RepositoryLookupEventProducer.class);

    private final KafkaTemplate<String, RepositoryLookupEvent> kafkaTemplate;

    public RepositoryLookupEventProducer(KafkaTemplate<String, RepositoryLookupEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(RepositoryLookupEvent event) {
        try {
            kafkaTemplate.send(TOPIC, event.username(), event)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.warn("Failed to publish repository lookup event for '{}'", event.username(), ex);
                        }
                    });
        } catch (Exception e) {
            log.warn("Failed to publish repository lookup event for '{}'", event.username(), e);
        }
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run:
```bash
./gradlew server:test --tests "com.redis.rediscachingjava.kafka.RepositoryLookupEventProducerTest"
```
Expected: `BUILD SUCCESSFUL`, 3 tests passed.

- [ ] **Step 7: Commit**

```bash
git add server/src/main/java/com/redis/rediscachingjava/kafka/RepositoryLookupEvent.java \
        server/src/main/java/com/redis/rediscachingjava/config/KafkaTopicConfig.java \
        server/src/main/java/com/redis/rediscachingjava/kafka/RepositoryLookupEventProducer.java \
        server/src/test/java/com/redis/rediscachingjava/kafka/RepositoryLookupEventProducerTest.java
git commit -m "feat: add RepositoryLookupEventProducer with resilient publish"
```

---

### Task 4: `RepositoryLookupEventConsumer`

**Files:**
- Create: `server/src/main/java/com/redis/rediscachingjava/kafka/RepositoryLookupEventConsumer.java`

No dedicated test, per the design spec: this is a single log statement with
no branching logic, consistent with this codebase's existing practice of
not writing tests for trivial passthrough code (e.g. the exception classes
from the prior refactor).

- [ ] **Step 1: Create the consumer**

```java
package com.redis.rediscachingjava.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class RepositoryLookupEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(RepositoryLookupEventConsumer.class);

    @KafkaListener(topics = "repository-lookups", groupId = "repository-lookup-consumer")
    public void onEvent(RepositoryLookupEvent event) {
        log.info("Received repository lookup event: {}", event);
    }
}
```

- [ ] **Step 2: Verify the module still builds**

Run:
```bash
./gradlew server:build
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add server/src/main/java/com/redis/rediscachingjava/kafka/RepositoryLookupEventConsumer.java
git commit -m "feat: add RepositoryLookupEventConsumer logging received events"
```

---

### Task 5: Wire the producer into `RepositoryController`

**Files:**
- Modify: `server/src/main/java/com/redis/rediscachingjava/web/RepositoryController.java`
- Modify: `server/src/test/java/com/redis/rediscachingjava/web/RepositoryControllerTest.java`

The controller publishes defensively (its own try/catch around the call),
as a second layer of protection on top of the producer's own internal
try/catch — so the HTTP response survives even if a test mocks the producer
itself to misbehave, not just real Kafka outages.

- [ ] **Step 1: Write the two new failing tests (added to the existing test class)**

Replace the full contents of
`server/src/test/java/com/redis/rediscachingjava/web/RepositoryControllerTest.java`
with:

```java
package com.redis.rediscachingjava.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.redis.rediscachingjava.config.SecurityConfig;
import com.redis.rediscachingjava.dto.RepositoryCountResponse;
import com.redis.rediscachingjava.exception.GitHubUserNotFoundException;
import com.redis.rediscachingjava.kafka.RepositoryLookupEvent;
import com.redis.rediscachingjava.kafka.RepositoryLookupEventProducer;
import com.redis.rediscachingjava.service.RepositoryLookupService;

@WebMvcTest(RepositoryController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "app.cors.allowed-origins=*")
class RepositoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RepositoryLookupService lookupService;

    @MockBean
    private RepositoryLookupEventProducer eventProducer;

    @Test
    void returnsRepositoryCountJsonAndResponseTimeHeader() throws Exception {
        when(lookupService.getRepositoryCount("octocat"))
                .thenReturn(new RepositoryCountResponse("octocat", "8", true));

        mockMvc.perform(get("/repos/octocat").header("Origin", "http://localhost:8081"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("octocat"))
                .andExpect(jsonPath("$.repos").value("8"))
                .andExpect(jsonPath("$.cached").value(true))
                .andExpect(header().exists("X-Response-Time"))
                .andExpect(header().string("Access-Control-Expose-Headers", "X-Response-Time"));
    }

    @Test
    void responseTimeHeaderEndsWithLiteralMs() throws Exception {
        when(lookupService.getRepositoryCount("octocat"))
                .thenReturn(new RepositoryCountResponse("octocat", "8", true));

        mockMvc.perform(get("/repos/octocat"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String headerValue = result.getResponse().getHeader("X-Response-Time");
                    assertThat(headerValue).endsWith("ms");
                });
    }

    @Test
    void returns404WhenUserNotFound() throws Exception {
        when(lookupService.getRepositoryCount("doesnotexist"))
                .thenThrow(new GitHubUserNotFoundException("doesnotexist"));

        mockMvc.perform(get("/repos/doesnotexist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void publishesLookupEventAfterSuccessfulLookup() throws Exception {
        when(lookupService.getRepositoryCount("octocat"))
                .thenReturn(new RepositoryCountResponse("octocat", "8", true));

        mockMvc.perform(get("/repos/octocat")).andExpect(status().isOk());

        ArgumentCaptor<RepositoryLookupEvent> captor = ArgumentCaptor.forClass(RepositoryLookupEvent.class);
        verify(eventProducer).publish(captor.capture());
        assertThat(captor.getValue().username()).isEqualTo("octocat");
        assertThat(captor.getValue().repos()).isEqualTo("8");
        assertThat(captor.getValue().cached()).isTrue();
    }

    @Test
    void stillReturns200WhenEventPublishThrows() throws Exception {
        when(lookupService.getRepositoryCount("octocat"))
                .thenReturn(new RepositoryCountResponse("octocat", "8", true));
        doThrow(new RuntimeException("kafka down")).when(eventProducer).publish(any());

        mockMvc.perform(get("/repos/octocat"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cached").value(true));
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:
```bash
./gradlew server:test --tests "com.redis.rediscachingjava.web.RepositoryControllerTest"
```
Expected: FAIL to compile — `RepositoryController`'s constructor doesn't
accept a `RepositoryLookupEventProducer` yet, and
`com.redis.rediscachingjava.kafka.RepositoryLookupEvent`/
`RepositoryLookupEventProducer` aren't referenced from `web` yet in a way
the controller understands (RED state — also note ALL 5 tests in this class
will fail at this point, not just the 2 new ones, since `@MockBean` for a
constructor parameter that doesn't exist yet breaks Spring context loading
for the whole test class).

- [ ] **Step 3: Update `RepositoryController`**

Replace the full contents of
`server/src/main/java/com/redis/rediscachingjava/web/RepositoryController.java`
with:

```java
package com.redis.rediscachingjava.web;

import java.text.DecimalFormat;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.redis.rediscachingjava.dto.RepositoryCountResponse;
import com.redis.rediscachingjava.kafka.RepositoryLookupEvent;
import com.redis.rediscachingjava.kafka.RepositoryLookupEventProducer;
import com.redis.rediscachingjava.service.RepositoryLookupService;

import jakarta.servlet.http.HttpServletResponse;

@RestController
public class RepositoryController {

    private static final Logger log = LoggerFactory.getLogger(RepositoryController.class);

    private final RepositoryLookupService lookupService;
    private final RepositoryLookupEventProducer eventProducer;

    public RepositoryController(
            RepositoryLookupService lookupService, RepositoryLookupEventProducer eventProducer) {
        this.lookupService = lookupService;
        this.eventProducer = eventProducer;
    }

    @GetMapping("/repos/{username}")
    public RepositoryCountResponse getRepositoryCount(
            @PathVariable String username, HttpServletResponse response) {
        long startNanos = System.nanoTime();
        RepositoryCountResponse result = lookupService.getRepositoryCount(username);
        response.addHeader("X-Response-Time", formatElapsedMillis(System.nanoTime() - startNanos));
        publishLookupEvent(result);
        return result;
    }

    private void publishLookupEvent(RepositoryCountResponse result) {
        try {
            eventProducer.publish(new RepositoryLookupEvent(
                    result.username(), result.repos(), result.cached(), Instant.now()));
        } catch (Exception e) {
            log.warn("Failed to publish repository lookup event", e);
        }
    }

    private static String formatElapsedMillis(long elapsedNanos) {
        DecimalFormat format = new DecimalFormat("#.###");
        return format.format(elapsedNanos / 1_000_000.0) + "ms";
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run:
```bash
./gradlew server:test --tests "com.redis.rediscachingjava.web.RepositoryControllerTest"
```
Expected: `BUILD SUCCESSFUL`, 5 tests passed.

- [ ] **Step 5: Run the full test suite**

Run:
```bash
./gradlew server:test
```
Expected: `BUILD SUCCESSFUL`, all tests across every file pass (18 from the
prior refactor + 3 new producer tests + 2 new controller tests = 23).

- [ ] **Step 6: Commit**

```bash
git add server/src/main/java/com/redis/rediscachingjava/web/RepositoryController.java \
        server/src/test/java/com/redis/rediscachingjava/web/RepositoryControllerTest.java
git commit -m "feat: publish repository lookup events from RepositoryController"
```

---

### Task 6: End-to-end verification against the real Kafka broker

This closes the loop: everything up to here is unit-tested with mocks. This
task proves the real, wired-up app actually produces and consumes events
against a real Kafka broker, and that the resilience contract holds when
Kafka is down — matching the design spec's "Manual verification" section.

**Files:** none (verification only).

- [ ] **Step 1: Ensure the full stack is running**

Run:
```bash
docker compose up -d
docker compose ps
```
Expected: `caching-demo-redis`, `caching-demo-kafka` both `healthy`;
`caching-demo-redisinsight`, `caching-demo-kafka-ui` both `Up`.

- [ ] **Step 2: Build and start the server**

Run:
```bash
./gradlew server:build
./gradlew server:bootRun
```
Wait until the log shows `Started RedisCachingJavaApplication`. Also watch
for `Received repository lookup event:` log lines once you hit the
endpoint in the next step — that's the in-app consumer proving the round
trip works.

- [ ] **Step 3: Trigger a lookup and confirm the event round-trips**

Run:
```bash
curl -s http://localhost:8080/repos/octocat
```
Expected: the same JSON response shape as before this feature
(`{"username":"octocat","repos":"8","cached":...}`). In the `bootRun`
terminal, confirm a log line appears shortly after:
`Received repository lookup event: RepositoryLookupEvent[username=octocat, repos=8, cached=..., timestamp=...]`.

- [ ] **Step 4: Confirm the message is visible in kafka-ui**

Open `http://localhost:8090` in a browser, navigate to the `local` cluster
→ Topics → `repository-lookups` → Messages. Confirm one message is present
with the username, repos count, and an ISO-8601 `timestamp` field (this
also confirms `Instant` serializes correctly via Spring Kafka's default
Jackson setup — no extra configuration was needed for this, per the design
spec).

- [ ] **Step 5: Resilience check — stop Kafka, confirm the endpoint still works**

Run:
```bash
docker compose stop kafka
curl -s -D - http://localhost:8080/repos/torvalds
```
Expected: still `200 OK` with the correct JSON body and `X-Response-Time`
header — identical behavior to before this feature existed. Check the
`bootRun` terminal log for a `WARN` line about the failed publish (proving
the failure was caught and logged, not swallowed silently, and not thrown).

- [ ] **Step 6: Restart Kafka and stop the server**

Run:
```bash
docker compose start kafka
pkill -9 -f RedisCachingJavaApplication
```

No commit for this task — it's verification only, nothing to check in.

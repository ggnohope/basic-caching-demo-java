# Server Professional Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor `server/` from a single tutorial-grade class into a layered, tested, Spring Boot 3 application, preserving the exact HTTP contract the Vue client depends on.

**Architecture:** Split `Repository.java` into `web` (controller), `service` (orchestration), `github` (HTTP client to GitHub), `cache` (Redis access), `dto` (response shape), `exception` (typed errors + `@RestControllerAdvice`), and `config` (security/CORS, RestClient). Migrate Jedis → Spring Data Redis (Lettuce) and `URLConnection` → `RestClient`. Full design rationale: `docs/superpowers/specs/2026-08-01-server-professional-refactor-design.md`.

**Tech Stack:** Spring Boot 3.3.5, Java 17, Gradle 8.10.2, Spring Data Redis (Lettuce), Spring Security 6, JUnit 5, Mockito, AssertJ, `MockRestServiceServer`.

---

## Contract reminder (do not break)

`GET /repos/{username}` → `200` JSON `{"username": "...", "repos": "...", "cached": true|false}`, header `X-Response-Time` formatted as a decimal number followed by the literal suffix `ms` (e.g. `"1.64ms"`) — the client does `duration.slice(0, duration.length - 2)`, so the suffix must be exactly `ms`. `Access-Control-Expose-Headers` must include `X-Response-Time`. Env vars `REDIS_URL`, `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`, `REDIS_DB` keep the same names and `REDIS_URL`-wins-if-non-empty semantics.

---

### Task 1: Bump the Gradle wrapper to 8.10.2

**Files:**
- Modify: `gradle/wrapper/gradle-wrapper.properties`
- Modify: `gradle/wrapper/gradle-wrapper.jar` (regenerated, binary)
- Modify: `gradlew`, `gradlew.bat` (regenerated)

- [ ] **Step 1: Run the wrapper task to bump Gradle**

Run (from repo root):
```bash
./gradlew wrapper --gradle-version 8.10.2 --distribution-type bin
```
Expected: `BUILD SUCCESSFUL`. This downloads Gradle 8.10.2 and rewrites `gradle/wrapper/gradle-wrapper.properties`, `gradle-wrapper.jar`, `gradlew`, `gradlew.bat`.

- [ ] **Step 2: Verify the wrapper now reports 8.10.2**

Run:
```bash
./gradlew --version
```
Expected: output includes `Gradle 8.10.2`.

- [ ] **Step 3: Commit**

```bash
git add gradle/wrapper/gradle-wrapper.properties gradle/wrapper/gradle-wrapper.jar gradlew gradlew.bat
git commit -m "build: bump Gradle wrapper to 8.10.2 for Spring Boot 3 support"
```

---

### Task 2: Migrate `server/` to Spring Boot 3.3.5 / Java 17 and remove obsolete tutorial code

This task rewrites the build file and deletes the two source files that cannot
compile under Spring Boot 3 (`javax.servlet` → `jakarta.servlet`,
`WebSecurityConfigurerAdapter` removed in Spring Security 6), plus the
`application.properties` → `application.yml` swap. The app is intentionally
non-functional (no controller yet) at the end of this task — later tasks add
it back layer by layer. This is the only task in the plan where the app
doesn't serve the endpoint; every task after this one keeps it compiling.

**Files:**
- Modify: `server/build.gradle`
- Delete: `server/src/main/java/com/redis/rediscachingjava/controller/Repository.java`
- Delete: `server/src/main/java/com/redis/rediscachingjava/security/SecurityConfig.java`
- Delete: `server/src/main/resources/application.properties`
- Create: `server/src/main/resources/application.yml`

- [ ] **Step 1: Rewrite `server/build.gradle`**

```groovy
plugins {
    id 'org.springframework.boot' version '3.3.5'
    id 'io.spring.dependency-management' version '1.1.6'
    id 'java'
}

group = 'com.redis'
version = '0.0.1-SNAPSHOT'

java {
    sourceCompatibility = '17'
}

springBoot {
    mainClass = 'com.redis.rediscachingjava.RedisCachingJavaApplication'
}

repositories {
    mavenCentral()
}

dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-security'
    implementation 'org.springframework.boot:spring-boot-starter-data-redis'
    developmentOnly 'org.springframework.boot:spring-boot-devtools'
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
}

tasks.named('test') {
    useJUnitPlatform()
}
```

- [ ] **Step 2: Delete the obsolete controller**

```bash
git rm server/src/main/java/com/redis/rediscachingjava/controller/Repository.java
```

- [ ] **Step 3: Delete the obsolete security config**

```bash
git rm server/src/main/java/com/redis/rediscachingjava/security/SecurityConfig.java
```
(A new one is created in `com.redis.rediscachingjava.config` in Task 7.)

- [ ] **Step 4: Replace `application.properties` with `application.yml`**

```bash
git rm server/src/main/resources/application.properties
```

Create `server/src/main/resources/application.yml`:
```yaml
server:
  port: 8080
  error:
    include-stacktrace: never
    include-message: never

spring:
  data:
    redis:
      url: ${REDIS_URL:}
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
      database: ${REDIS_DB:0}

app:
  github:
    base-url: ${GITHUB_API_BASE_URL:https://api.github.com}
  cache:
    repository-ttl: ${REPO_CACHE_TTL:PT1H}
  cors:
    allowed-origins: ${CORS_ALLOWED_ORIGINS:*}
```

- [ ] **Step 5: Verify the module builds with no source files left**

Run:
```bash
./gradlew server:build
```
Expected: `BUILD SUCCESSFUL`. There are no controllers/tests yet — this only
proves the new Boot 3 / Java 17 toolchain and dependency set resolve and
compile cleanly.

- [ ] **Step 6: Commit**

```bash
git add server/build.gradle server/src/main/resources/application.yml
git commit -m "build: migrate server to Spring Boot 3.3.5 / Java 17, drop tutorial Jedis controller"
```

---

### Task 3: `RepositoryCountResponse` DTO

**Files:**
- Create: `server/src/main/java/com/redis/rediscachingjava/dto/RepositoryCountResponse.java`
- Test: `server/src/test/java/com/redis/rediscachingjava/dto/RepositoryCountResponseJsonTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.redis.rediscachingjava.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class RepositoryCountResponseJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serializesWithExactFieldNames() throws Exception {
        RepositoryCountResponse response = new RepositoryCountResponse("octocat", "8", false);

        String json = objectMapper.writeValueAsString(response);

        assertThat(json).isEqualTo("{\"username\":\"octocat\",\"repos\":\"8\",\"cached\":false}");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
./gradlew server:test --tests "com.redis.rediscachingjava.dto.RepositoryCountResponseJsonTest"
```
Expected: FAIL to compile — `RepositoryCountResponse` does not exist yet. This
compile failure is the RED state (accepted TDD red state in a statically
typed language).

- [ ] **Step 3: Create the record**

```java
package com.redis.rediscachingjava.dto;

public record RepositoryCountResponse(String username, String repos, boolean cached) {
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:
```bash
./gradlew server:test --tests "com.redis.rediscachingjava.dto.RepositoryCountResponseJsonTest"
```
Expected: `BUILD SUCCESSFUL`, 1 test passed. This also confirms Jackson
serializes Java records using the bare component names (`username`, `repos`,
`cached`) with no extra module needed — required for the wire contract.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/com/redis/rediscachingjava/dto/RepositoryCountResponse.java \
        server/src/test/java/com/redis/rediscachingjava/dto/RepositoryCountResponseJsonTest.java
git commit -m "feat: add RepositoryCountResponse DTO"
```

---

### Task 4: GitHub client (`GitHubUser`, exceptions, `RestClientConfig`, `GitHubClient`)

**Files:**
- Create: `server/src/main/java/com/redis/rediscachingjava/github/GitHubUser.java`
- Create: `server/src/main/java/com/redis/rediscachingjava/exception/GitHubUserNotFoundException.java`
- Create: `server/src/main/java/com/redis/rediscachingjava/exception/GitHubApiException.java`
- Create: `server/src/main/java/com/redis/rediscachingjava/config/RestClientConfig.java`
- Create: `server/src/main/java/com/redis/rediscachingjava/github/GitHubClient.java`
- Test: `server/src/test/java/com/redis/rediscachingjava/github/GitHubClientTest.java`

- [ ] **Step 1: Create the GitHub response record**

```java
package com.redis.rediscachingjava.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubUser(@JsonProperty("public_repos") Integer publicRepos) {
}
```

- [ ] **Step 2: Create the two exception types**

```java
package com.redis.rediscachingjava.exception;

public class GitHubUserNotFoundException extends RuntimeException {
    public GitHubUserNotFoundException(String username) {
        super("GitHub user '" + username + "' not found");
    }
}
```

```java
package com.redis.rediscachingjava.exception;

public class GitHubApiException extends RuntimeException {
    public GitHubApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 3: Create the `RestClient` bean**

```java
package com.redis.rediscachingjava.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClient gitHubRestClient(@Value("${app.github.base-url}") String baseUrl) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofSeconds(5))
                .withReadTimeout(Duration.ofSeconds(5));

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
    }
}
```

- [ ] **Step 4: Write the failing test for `GitHubClient`**

```java
package com.redis.rediscachingjava.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.redis.rediscachingjava.exception.GitHubApiException;
import com.redis.rediscachingjava.exception.GitHubUserNotFoundException;

class GitHubClientTest {

    private static final String BASE_URL = "https://api.github.com";

    private MockRestServiceServer mockServer;
    private GitHubClient gitHubClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        mockServer = MockRestServiceServer.bindTo(builder).build();
        gitHubClient = new GitHubClient(builder.build());
    }

    @Test
    void returnsPublicRepoCountWhenUserExists() {
        mockServer.expect(requestTo(BASE_URL + "/users/octocat"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"public_repos\": 8}", MediaType.APPLICATION_JSON));

        int result = gitHubClient.fetchPublicRepoCount("octocat");

        assertThat(result).isEqualTo(8);
    }

    @Test
    void throwsUserNotFoundWhenGitHubReturns404() {
        mockServer.expect(requestTo(BASE_URL + "/users/doesnotexist"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> gitHubClient.fetchPublicRepoCount("doesnotexist"))
                .isInstanceOf(GitHubUserNotFoundException.class);
    }

    @Test
    void throwsUserNotFoundWhenPublicReposFieldMissing() {
        mockServer.expect(requestTo(BASE_URL + "/users/weirdaccount"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"login\": \"weirdaccount\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gitHubClient.fetchPublicRepoCount("weirdaccount"))
                .isInstanceOf(GitHubUserNotFoundException.class);
    }

    @Test
    void throwsGitHubApiExceptionOnServerError() {
        mockServer.expect(requestTo(BASE_URL + "/users/octocat"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> gitHubClient.fetchPublicRepoCount("octocat"))
                .isInstanceOf(GitHubApiException.class);
    }
}
```

- [ ] **Step 5: Run the test to verify it fails**

Run:
```bash
./gradlew server:test --tests "com.redis.rediscachingjava.github.GitHubClientTest"
```
Expected: FAIL to compile — `GitHubClient` does not exist yet (RED state).

- [ ] **Step 6: Implement `GitHubClient`**

```java
package com.redis.rediscachingjava.github;

import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.redis.rediscachingjava.exception.GitHubApiException;
import com.redis.rediscachingjava.exception.GitHubUserNotFoundException;

@Component
public class GitHubClient {

    private final RestClient gitHubRestClient;

    public GitHubClient(RestClient gitHubRestClient) {
        this.gitHubRestClient = gitHubRestClient;
    }

    public int fetchPublicRepoCount(String username) {
        GitHubUser user;
        try {
            user = gitHubRestClient.get()
                    .uri("/users/{username}", username)
                    .retrieve()
                    .body(GitHubUser.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw new GitHubUserNotFoundException(username);
        } catch (RestClientException e) {
            throw new GitHubApiException("Failed to fetch GitHub user '" + username + "'", e);
        }

        if (user == null || user.publicRepos() == null) {
            throw new GitHubUserNotFoundException(username);
        }

        return user.publicRepos();
    }
}
```

- [ ] **Step 7: Run the test to verify it passes**

Run:
```bash
./gradlew server:test --tests "com.redis.rediscachingjava.github.GitHubClientTest"
```
Expected: `BUILD SUCCESSFUL`, 4 tests passed.

- [ ] **Step 8: Commit**

```bash
git add server/src/main/java/com/redis/rediscachingjava/github/ \
        server/src/main/java/com/redis/rediscachingjava/exception/ \
        server/src/main/java/com/redis/rediscachingjava/config/RestClientConfig.java \
        server/src/test/java/com/redis/rediscachingjava/github/GitHubClientTest.java
git commit -m "feat: add GitHubClient using RestClient, replacing raw URLConnection"
```

---

### Task 5: `RepositoryCountCache`

**Files:**
- Create: `server/src/main/java/com/redis/rediscachingjava/cache/RepositoryCountCache.java`
- Test: `server/src/test/java/com/redis/rediscachingjava/cache/RepositoryCountCacheTest.java`

- [ ] **Step 1: Write the failing test**

```java
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
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
./gradlew server:test --tests "com.redis.rediscachingjava.cache.RepositoryCountCacheTest"
```
Expected: FAIL to compile — `RepositoryCountCache` does not exist yet (RED state).

- [ ] **Step 3: Implement `RepositoryCountCache`**

```java
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
```

- [ ] **Step 4: Run the test to verify it passes**

Run:
```bash
./gradlew server:test --tests "com.redis.rediscachingjava.cache.RepositoryCountCacheTest"
```
Expected: `BUILD SUCCESSFUL`, 3 tests passed.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/com/redis/rediscachingjava/cache/ \
        server/src/test/java/com/redis/rediscachingjava/cache/
git commit -m "feat: add RepositoryCountCache backed by Spring Data Redis"
```

---

### Task 6: `RepositoryLookupService`

**Files:**
- Create: `server/src/main/java/com/redis/rediscachingjava/service/RepositoryLookupService.java`
- Test: `server/src/test/java/com/redis/rediscachingjava/service/RepositoryLookupServiceTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.redis.rediscachingjava.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.redis.rediscachingjava.cache.RepositoryCountCache;
import com.redis.rediscachingjava.dto.RepositoryCountResponse;
import com.redis.rediscachingjava.exception.GitHubUserNotFoundException;
import com.redis.rediscachingjava.github.GitHubClient;

class RepositoryLookupServiceTest {

    private GitHubClient gitHubClient;
    private RepositoryCountCache cache;
    private RepositoryLookupService service;

    @BeforeEach
    void setUp() {
        gitHubClient = mock(GitHubClient.class);
        cache = mock(RepositoryCountCache.class);
        service = new RepositoryLookupService(gitHubClient, cache);
    }

    @Test
    void returnsCachedValueWithoutCallingGitHub() {
        when(cache.get("octocat")).thenReturn(Optional.of("8"));

        RepositoryCountResponse result = service.getRepositoryCount("octocat");

        assertThat(result).isEqualTo(new RepositoryCountResponse("octocat", "8", true));
        verify(gitHubClient, never()).fetchPublicRepoCount(any());
    }

    @Test
    void fetchesFromGitHubAndCachesOnMiss() {
        when(cache.get("torvalds")).thenReturn(Optional.empty());
        when(gitHubClient.fetchPublicRepoCount("torvalds")).thenReturn(12);

        RepositoryCountResponse result = service.getRepositoryCount("torvalds");

        assertThat(result).isEqualTo(new RepositoryCountResponse("torvalds", "12", false));
        verify(cache).put("torvalds", "12");
    }

    @Test
    void propagatesGitHubUserNotFoundException() {
        when(cache.get("doesnotexist")).thenReturn(Optional.empty());
        when(gitHubClient.fetchPublicRepoCount("doesnotexist"))
                .thenThrow(new GitHubUserNotFoundException("doesnotexist"));

        assertThatThrownBy(() -> service.getRepositoryCount("doesnotexist"))
                .isInstanceOf(GitHubUserNotFoundException.class);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
./gradlew server:test --tests "com.redis.rediscachingjava.service.RepositoryLookupServiceTest"
```
Expected: FAIL to compile — `RepositoryLookupService` does not exist yet (RED state).

- [ ] **Step 3: Implement `RepositoryLookupService`**

```java
package com.redis.rediscachingjava.service;

import org.springframework.stereotype.Service;

import com.redis.rediscachingjava.cache.RepositoryCountCache;
import com.redis.rediscachingjava.dto.RepositoryCountResponse;
import com.redis.rediscachingjava.github.GitHubClient;

@Service
public class RepositoryLookupService {

    private final GitHubClient gitHubClient;
    private final RepositoryCountCache cache;

    public RepositoryLookupService(GitHubClient gitHubClient, RepositoryCountCache cache) {
        this.gitHubClient = gitHubClient;
        this.cache = cache;
    }

    public RepositoryCountResponse getRepositoryCount(String username) {
        return cache.get(username)
                .map(repos -> new RepositoryCountResponse(username, repos, true))
                .orElseGet(() -> fetchAndCache(username));
    }

    private RepositoryCountResponse fetchAndCache(String username) {
        int publicRepos = gitHubClient.fetchPublicRepoCount(username);
        String repos = String.valueOf(publicRepos);
        cache.put(username, repos);
        return new RepositoryCountResponse(username, repos, false);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:
```bash
./gradlew server:test --tests "com.redis.rediscachingjava.service.RepositoryLookupServiceTest"
```
Expected: `BUILD SUCCESSFUL`, 3 tests passed.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/com/redis/rediscachingjava/service/ \
        server/src/test/java/com/redis/rediscachingjava/service/
git commit -m "feat: add RepositoryLookupService orchestrating cache and GitHub client"
```

---

### Task 7: `SecurityConfig` (CORS + permitAll, new location)

**Files:**
- Create: `server/src/main/java/com/redis/rediscachingjava/config/SecurityConfig.java`

No dedicated unit test here — `SecurityFilterChain`/CORS wiring is exercised
end-to-end by `RepositoryControllerTest` in Task 9 via `@Import`.

- [ ] **Step 1: Create `SecurityConfig`**

```java
package com.redis.rediscachingjava.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfig {

    private final String allowedOrigins;

    public SecurityConfig(@Value("${app.cors.allowed-origins}") String allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()));
        return http.build();
    }

    private CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of(allowedOrigins.split(",")));
        configuration.setAllowedMethods(List.of("GET"));
        configuration.setExposedHeaders(List.of("X-Response-Time"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
```

CSRF is disabled because this is a stateless, cookie-free JSON API serving
only `GET` — there is no session-based state-changing request for CSRF to
protect. `allowedMethods` is narrowed to `GET` (the only method this API ever
serves) instead of the old `*`.

- [ ] **Step 2: Verify the module still builds**

Run:
```bash
./gradlew server:build
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add server/src/main/java/com/redis/rediscachingjava/config/SecurityConfig.java
git commit -m "feat: add SecurityConfig with SecurityFilterChain, replacing WebSecurityConfigurerAdapter"
```

---

### Task 8: `ApiExceptionHandler`

**Files:**
- Create: `server/src/main/java/com/redis/rediscachingjava/exception/ApiExceptionHandler.java`
- Test: `server/src/test/java/com/redis/rediscachingjava/exception/ApiExceptionHandlerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.redis.rediscachingjava.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void mapsUserNotFoundTo404() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleUserNotFound(new GitHubUserNotFoundException("octocat"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("status", 404);
    }

    @Test
    void mapsGitHubApiExceptionTo502() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleGitHubApiError(new GitHubApiException("boom", new RuntimeException()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    void mapsRedisConnectionFailureTo503() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleRedisUnavailable(new RedisConnectionFailureException("boom"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void mapsUnexpectedExceptionTo500() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleUnexpected(new RuntimeException("boom"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
./gradlew server:test --tests "com.redis.rediscachingjava.exception.ApiExceptionHandlerTest"
```
Expected: FAIL to compile — `ApiExceptionHandler` does not exist yet (RED state).

- [ ] **Step 3: Implement `ApiExceptionHandler`**

```java
package com.redis.rediscachingjava.exception;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(GitHubUserNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleUserNotFound(GitHubUserNotFoundException e) {
        return errorResponse(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(GitHubApiException.class)
    public ResponseEntity<Map<String, Object>> handleGitHubApiError(GitHubApiException e) {
        log.error("GitHub API call failed", e);
        return errorResponse(HttpStatus.BAD_GATEWAY, "Failed to reach GitHub");
    }

    @ExceptionHandler(RedisConnectionFailureException.class)
    public ResponseEntity<Map<String, Object>> handleRedisUnavailable(RedisConnectionFailureException e) {
        log.error("Redis connection failed", e);
        return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, "Cache temporarily unavailable");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e) {
        log.error("Unexpected error", e);
        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
    }

    private ResponseEntity<Map<String, Object>> errorResponse(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "status", status.value(),
                "error", status.getReasonPhrase(),
                "message", message
        ));
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:
```bash
./gradlew server:test --tests "com.redis.rediscachingjava.exception.ApiExceptionHandlerTest"
```
Expected: `BUILD SUCCESSFUL`, 4 tests passed.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/com/redis/rediscachingjava/exception/ApiExceptionHandler.java \
        server/src/test/java/com/redis/rediscachingjava/exception/
git commit -m "feat: add ApiExceptionHandler with clean error bodies (no stack traces)"
```

---

### Task 9: `RepositoryController`

**Files:**
- Create: `server/src/main/java/com/redis/rediscachingjava/web/RepositoryController.java`
- Test: `server/src/test/java/com/redis/rediscachingjava/web/RepositoryControllerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.redis.rediscachingjava.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.redis.rediscachingjava.config.SecurityConfig;
import com.redis.rediscachingjava.dto.RepositoryCountResponse;
import com.redis.rediscachingjava.exception.GitHubUserNotFoundException;
import com.redis.rediscachingjava.service.RepositoryLookupService;

@WebMvcTest(RepositoryController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "app.cors.allowed-origins=*")
class RepositoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RepositoryLookupService lookupService;

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
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
./gradlew server:test --tests "com.redis.rediscachingjava.web.RepositoryControllerTest"
```
Expected: FAIL to compile — `RepositoryController` does not exist yet (RED state).

- [ ] **Step 3: Implement `RepositoryController`**

```java
package com.redis.rediscachingjava.web;

import java.text.DecimalFormat;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.redis.rediscachingjava.dto.RepositoryCountResponse;
import com.redis.rediscachingjava.service.RepositoryLookupService;

import jakarta.servlet.http.HttpServletResponse;

@RestController
public class RepositoryController {

    private final RepositoryLookupService lookupService;

    public RepositoryController(RepositoryLookupService lookupService) {
        this.lookupService = lookupService;
    }

    @GetMapping("/repos/{username}")
    public RepositoryCountResponse getRepositoryCount(
            @PathVariable String username, HttpServletResponse response) {
        long startNanos = System.nanoTime();
        RepositoryCountResponse result = lookupService.getRepositoryCount(username);
        response.addHeader("X-Response-Time", formatElapsedMillis(System.nanoTime() - startNanos));
        return result;
    }

    private static String formatElapsedMillis(long elapsedNanos) {
        DecimalFormat format = new DecimalFormat("#.###");
        return format.format(elapsedNanos / 1_000_000.0) + "ms";
    }
}
```

`Access-Control-Expose-Headers` is no longer set by hand in the controller —
`SecurityConfig`'s `CorsConfiguration.setExposedHeaders(List.of("X-Response-Time"))`
(Task 7) makes Spring's CORS processing add it automatically on cross-origin
requests, which is what the test above verifies.

- [ ] **Step 4: Run the test to verify it passes**

Run:
```bash
./gradlew server:test --tests "com.redis.rediscachingjava.web.RepositoryControllerTest"
```
Expected: `BUILD SUCCESSFUL`, 3 tests passed.

- [ ] **Step 5: Run the full test suite**

Run:
```bash
./gradlew server:test
```
Expected: `BUILD SUCCESSFUL`, all tests across every task pass.

- [ ] **Step 6: Commit**

```bash
git add server/src/main/java/com/redis/rediscachingjava/web/ \
        server/src/test/java/com/redis/rediscachingjava/web/
git commit -m "feat: add RepositoryController, restoring the /repos/{username} endpoint"
```

---

### Task 10: Update `Procfile` for jar packaging

**Files:**
- Modify: `Procfile`

- [ ] **Step 1: Change the jar glob**

In `Procfile`, replace:
```
web: java -Dserver.port=$PORT $JAVA_OPTS -jar server/build/libs/*.war
```
with:
```
web: java -Dserver.port=$PORT $JAVA_OPTS -jar server/build/libs/*.jar
```
(`server/build.gradle` no longer applies the `war` plugin as of Task 2, so
the build now produces a `.jar`, not a `.war`.)

- [ ] **Step 2: Commit**

```bash
git add Procfile
git commit -m "build: update Procfile for jar packaging"
```

---

### Task 11: End-to-end verification against real Redis

This closes the loop: everything up to here is unit-tested with mocks, per
the spec's testing decision. This task proves the real, wired-up app still
meets the exact contract, the same way it was manually verified earlier this
session.

**Files:** none (verification only).

- [ ] **Step 1: Ensure Redis is running**

Run (from repo root):
```bash
docker compose up -d
docker compose ps
```
Expected: `caching-demo-redis` shows `healthy`.

- [ ] **Step 2: Full build**

Run:
```bash
./gradlew server:build
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Start the server**

Run (background):
```bash
./gradlew server:bootRun
```
Wait until the log shows `Started RedisCachingJavaApplication`.

- [ ] **Step 4: Verify cache-miss then cache-hit for a fresh username**

Run:
```bash
docker compose exec redis redis-cli del ggnohope-verify > /dev/null
curl -s -D - http://localhost:8080/repos/ggnohope-verify -o /tmp/resp1.json
cat /tmp/resp1.json
curl -s -D - http://localhost:8080/repos/ggnohope-verify -o /tmp/resp2.json
cat /tmp/resp2.json
```
Expected:
- First response: `200`, header `X-Response-Time` ending in `ms`, body
  `"cached":false`.
- Second response: `200`, header `X-Response-Time` ending in `ms` (a much
  smaller number), body `"cached":true`.

- [ ] **Step 5: Verify 404 for a nonexistent GitHub user**

Run:
```bash
curl -s -o /dev/null -w "%{http_code}\n" \
  http://localhost:8080/repos/this-user-should-not-exist-zzz999
```
Expected: `404`.

- [ ] **Step 6: Verify no stack trace leaks in the error body**

Run:
```bash
curl -s http://localhost:8080/repos/this-user-should-not-exist-zzz999
```
Expected: JSON body with `status`, `error`, `message` keys only — no
`"trace"` field (this is the exact leak observed and fixed this session).

- [ ] **Step 7: Stop the server**

Run:
```bash
pkill -f RedisCachingJavaApplication || pkill -f bootRun
```

No commit for this task — it's verification only, nothing to check in.

# Server Professional Refactor — Design

Date: 2026-08-01
Scope: `server/` (Spring Boot backend) only. `client/` (Vue) is out of scope and unchanged.

## Problem

The current server is tutorial-grade: one class (`Repository.java`) mixes Redis
connection setup, GitHub HTTP calls, JSON parsing, caching logic, and the REST
endpoint. Concrete issues observed (including live, during this session's
debugging):

- A single shared `Jedis` instance is created once at application boot
  (`onApplicationEvent`) with no pooling and no reconnect logic. If Redis is
  not yet reachable at boot, or restarts, the socket becomes permanently
  broken ("Attempting to read from a broken connection") until the whole
  server process is restarted. This happened during the previous debugging
  session in this repo.
- `spring-boot-devtools` on the classpath implicitly sets
  `server.error.include-stacktrace=ALWAYS`, and nothing in the app overrides
  it — full Java stack traces are returned to HTTP clients on error
  (observed directly in this session's 500 responses).
- Connection-init failures are silently swallowed (`catch (Exception
  ignored)`), so a misconfigured Redis connection fails invisibly.
- Config uses `@Value` with empty-string sentinels
  (`if (!redisPassword.equals(""))`) instead of typed, framework-native
  config.
- No layering: HTTP handling, business logic, GitHub client, and cache access
  are not separable or independently testable.
- No tests exist.
- Uses `java.net.URLConnection` directly for the GitHub call instead of a
  managed HTTP client.
- `WebSecurityConfigurerAdapter` is deprecated and removed in Spring Security
  6 (ships with Spring Boot 3), so any move to Boot 3 requires this to change
  regardless.

## Goals

- Refactor `server/` to a layered, testable, professional-standard Spring
  Boot 3 application.
- Preserve the existing HTTP contract exactly (see "Contract to preserve"
  below) so `client/` requires zero changes.
- Preserve all existing deployment environment variable names so Heroku /
  Google Cloud Run deploy buttons in `marketplace.json` / `app.json` keep
  working unmodified.
- Fix the two live bugs found this session (broken-forever Redis connection;
  stack trace leaking to HTTP clients) as a natural consequence of the
  refactor, not as a bolted-on patch.
- Add unit test coverage (mocked collaborators — no Testcontainers, per
  decision below).

## Non-goals

- No client (Vue) changes.
- No new features (auth, rate limiting, multiple cache backends, etc.).
- No change to the public URL shape or JSON field names.
- No CI/CD pipeline changes (out of scope for this spec).

## Contract to preserve

Verified against `client/src/components/Example.vue` and
`client/src/storage.js`:

- `GET /repos/{username}` → `200` with JSON body:
  `{"username": "<string>", "repos": "<string>", "cached": <boolean>}`
- Response header `X-Response-Time`, formatted as a decimal number followed
  by the literal suffix `ms` (e.g. `"1.64ms"`). This is a hard requirement:
  `client/src/storage.js` does `duration.slice(0, duration.length - 2)` to
  strip exactly two trailing characters, so the suffix must be exactly `ms`.
- `Access-Control-Expose-Headers` must include `X-Response-Time` (client
  reads it cross-origin via axios in local dev, where client and server run
  on different ports).
- CORS must allow the client's origin for `GET` (currently allows all
  origins/methods — see "Deliberate behavior changes" for the narrowing to
  GET-only).
- Environment variables read by the app: `REDIS_URL`, `REDIS_HOST`,
  `REDIS_PORT`, `REDIS_PASSWORD`, `REDIS_DB` — same names, same semantics
  (`REDIS_URL` wins when non-empty, matching Heroku's `heroku-redis` addon
  convention from `app.json`).
- On error, the client only does `console.log(err)` and swallows it — no
  client code depends on error response shape or exact status code, which
  gives freedom to clean up error responses/status codes on the server side.

## Deliberate behavior changes (non-breaking for the client)

These are intentional corrections, called out explicitly since "identical
behavior" is not literally true in these spots — verified as safe given the
contract above:

- CORS `allowedMethods` narrowed from `*` to `GET` only (the only method this
  API ever serves). Least-privilege; client only ever issues GET.
- Upstream GitHub failures (network error, unexpected response shape, non-404
  error status) now return `502 Bad Gateway` instead of `500`. GitHub
  user-not-found stays `404`. Redis unavailability returns `503 Service
  Unavailable` instead of an unmapped `500`. The client does not branch on
  status code, so this is safe, and it's a more correct REST status choice.
- Stack traces are no longer included in error responses
  (`server.error.include-stacktrace=never`), closing the leak observed this
  session.

## Architecture

```
com.redis.rediscachingjava
├── RedisCachingJavaApplication.java
├── config/
│   ├── SecurityConfig.java       — SecurityFilterChain bean, CORS config, CSRF disabled
│   └── RestClientConfig.java     — RestClient bean for GitHub calls (base URL, timeouts)
├── web/
│   └── RepositoryController.java — thin HTTP layer: times the call, delegates, sets header
├── service/
│   └── RepositoryLookupService.java — orchestration: cache-hit short-circuit, else fetch+store
├── github/
│   ├── GitHubClient.java         — RestClient wrapper, GitHub-specific error mapping
│   └── GitHubUser.java           — record, @JsonIgnoreProperties(ignoreUnknown = true)
├── cache/
│   └── RepositoryCountCache.java — thin wrapper over StringRedisTemplate (get / setex)
├── dto/
│   └── RepositoryCountResponse.java — record: username, repos, cached
└── exception/
    ├── GitHubUserNotFoundException.java
    ├── GitHubApiException.java
    └── ApiExceptionHandler.java  — @RestControllerAdvice, maps exceptions to status + clean JSON body, logs via SLF4J
```

Each unit has one job and depends on its collaborators through the
constructor, so each can be tested in isolation with mocks. No interface is
introduced for `RepositoryLookupService` since there is exactly one
implementation and no second implementation is anticipated (YAGNI) — Mockito
can mock the concrete class directly.

## Data flow

**Happy path (cache miss → GitHub → cache):**

1. Client sends `GET /repos/{username}`.
2. `RepositoryController` starts a timer, calls
   `RepositoryLookupService.getRepositoryCount(username)`.
3. Service calls `RepositoryCountCache.get(username)` (Redis `GET`).
4. Cache miss → `GitHubClient.fetchPublicRepoCount(username)` issues
   `GET https://api.github.com/users/{username}` via `RestClient`.
   - GitHub `200` with `public_repos` present → parsed to `Integer`.
   - GitHub `404`, or `200` with `public_repos` missing/null →
     `GitHubUserNotFoundException`.
   - Any other error (timeout, DNS, unexpected 5xx, malformed body) →
     `GitHubApiException`.
5. Service stores the result via `RepositoryCountCache.put(username, value,
   ttl)` (Redis `SETEX`, TTL from config, default 1 hour — same as today).
6. Service returns `RepositoryCountResponse(username, repos, cached=false)`.
7. Controller computes elapsed time, formats as `"<n>.<ddd>ms"`, sets
   `X-Response-Time` header, returns `200` with the JSON body. Jackson
   serializes Java records using the component names directly (with Spring
   Boot 3's built-in Jackson record support), so field names stay `username`,
   `repos`, `cached` with no custom serializer needed.

**Cache-hit path:** step 3 returns a value → skip step 4–5, return
`cached=true` directly.

**Error path:** any exception from steps 3–5 propagates to
`ApiExceptionHandler`, which maps it to the appropriate status code (404 /
502 / 503), logs the real exception server-side via SLF4J at `ERROR`, and
returns a clean JSON error body (no stack trace):
`{"status": <int>, "error": "<reason phrase>", "message": "<safe message>"}`.

## Redis client: Jedis → Spring Data Redis (Lettuce)

Replaces the hand-rolled single-`Jedis`-instance connection with Spring Data
Redis's `LettuceConnectionFactory`, which pools connections and reconnects
automatically. This directly eliminates the "connection permanently broken
until server restart" bug hit during this session's debugging.

Config (`application.yml`):

```yaml
spring:
  data:
    redis:
      url: ${REDIS_URL:}
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
      database: ${REDIS_DB:0}
```

Spring Boot's Redis auto-configuration already implements "use `url` if
non-empty, otherwise fall back to discrete host/port/password/database" —
this is exactly the behavior the old manual `if (!redisUrl.equals(""))`
branch implemented by hand in `Repository.java`, now handled by the
framework.

## GitHub HTTP client: `URLConnection` → `RestClient`

`RestClient` (Spring Framework 6.1, the standard blocking client as of Spring
Boot 3.2+, not deprecated like `RestTemplate`). Configured with explicit
connect/read timeouts (none existed before — an unbounded `URLConnection`
call could hang the request thread indefinitely; this closes that gap).

## Error handling details

`server.error.include-stacktrace=never` and `server.error.include-message=never`
set explicitly in `application.yml`, so the fix holds regardless of whether
`spring-boot-devtools` is on the classpath (devtools only ships in local
dev runs, never in the packaged jar, but explicit config removes the
ambiguity entirely).

`ApiExceptionHandler` (`@RestControllerAdvice`):

| Exception                         | Status | Notes                                   |
|------------------------------------|--------|------------------------------------------|
| `GitHubUserNotFoundException`      | 404    | Same as current behavior                 |
| `GitHubApiException`               | 502    | Was unmapped 500 before                  |
| `RedisConnectionFailureException`  | 503    | Was unmapped 500 before                  |
| (anything else, fallback)          | 500    | Logged at ERROR, generic safe message    |

## Testing (unit only, mocked collaborators — per decision)

- `RepositoryLookupServiceTest` — mocks `GitHubClient` and
  `RepositoryCountCache` (Mockito). Covers: cache hit skips GitHub call;
  cache miss calls GitHub then stores with correct TTL; exceptions from
  either collaborator propagate unchanged.
- `GitHubClientTest` — uses `MockRestServiceServer.bindTo(RestClient.Builder)`
  (officially supported since Spring Framework 6.1) to stub GitHub HTTP
  responses without real network calls or Docker. Covers: 200 with
  `public_repos`, 404, 200 with missing field, malformed JSON, timeout.
- `RepositoryCountCacheTest` — mocks `StringRedisTemplate` /
  `ValueOperations`. Covers: get returns `Optional.empty()` on miss, correct
  key/value/TTL passed to `set`.
- `RepositoryControllerTest` — `@WebMvcTest` + `MockMvc`, mocks
  `RepositoryLookupService`. Covers: JSON body shape, `X-Response-Time`
  header format (ends in literal `ms`), `Access-Control-Expose-Headers`,
  status code mapping for each exception type via `ApiExceptionHandler`.

## Build / config changes

- Spring Boot `2.4.1` → `3.3.x`; `sourceCompatibility` `1.8` → `17`.
- Gradle wrapper `6.7` → `8.x` (required for the Spring Boot 3 Gradle
  plugin).
- Drop `war` plugin and `providedRuntime` Tomcat dependency; produce a plain
  executable `jar` (no external servlet container deployment target exists
  for this app — the WAR packaging was vestigial). Update `Procfile`:
  `java -jar server/build/libs/*.war` → `*.jar`.
- Dependencies: remove `gson` and `jedis`; add
  `spring-boot-starter-data-redis`. Keep `spring-boot-starter-web`,
  `spring-boot-starter-security`, `spring-boot-starter-test` (JUnit 5 +
  Mockito, already included), `spring-boot-devtools` (developmentOnly,
  unchanged).
- `application.properties` → `application.yml` (nesting needed for
  `spring.data.redis.*`).
- New optional config knobs, all with defaults matching current behavior —
  no deployment changes required:
  - `app.github.base-url` (default `https://api.github.com`)
  - `app.cache.repository-ttl` (default `PT1H`, i.e. 1 hour — same as the
    hardcoded `3600` today)
  - `app.cors.allowed-origins` (default `*`, same as today)
- No Lombok (per decision) — constructors and loggers written by hand.

## Out of scope for this spec (explicitly not doing)

- Testcontainers / integration tests (unit tests with mocks only, per
  decision).
- Client (Vue) changes.
- New authentication/authorization.
- Rate limiting, multi-region cache, or any other feature not present today.

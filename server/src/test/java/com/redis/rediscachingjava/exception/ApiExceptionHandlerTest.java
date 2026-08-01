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

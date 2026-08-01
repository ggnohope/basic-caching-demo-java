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

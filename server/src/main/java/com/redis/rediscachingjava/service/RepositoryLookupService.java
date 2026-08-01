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

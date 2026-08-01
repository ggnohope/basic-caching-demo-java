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

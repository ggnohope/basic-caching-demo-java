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

package com.redis.rediscachingjava.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubUser(@JsonProperty("public_repos") Integer publicRepos) {
}

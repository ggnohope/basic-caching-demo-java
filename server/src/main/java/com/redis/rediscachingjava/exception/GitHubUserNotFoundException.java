package com.redis.rediscachingjava.exception;

public class GitHubUserNotFoundException extends RuntimeException {
    public GitHubUserNotFoundException(String username) {
        super("GitHub user '" + username + "' not found");
    }
}

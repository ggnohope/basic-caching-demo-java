package com.redis.rediscachingjava.config;

import io.lettuce.core.RedisURI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.util.StringUtils;

@Configuration
public class RedisConfig {

    @Bean
    public LettuceConnectionFactory redisConnectionFactory(
            @Value("${REDIS_URL:}") String redisUrl,
            @Value("${REDIS_HOST:localhost}") String redisHost,
            @Value("${REDIS_PORT:6379}") int redisPort,
            @Value("${REDIS_PASSWORD:}") String redisPassword,
            @Value("${REDIS_DB:0}") int redisDatabase) {

        RedisStandaloneConfiguration config = StringUtils.hasText(redisUrl)
                ? fromUrl(redisUrl)
                : fromDiscreteProperties(redisHost, redisPort, redisPassword, redisDatabase);

        return new LettuceConnectionFactory(config);
    }

    private static RedisStandaloneConfiguration fromUrl(String redisUrl) {
        RedisURI uri = RedisURI.create(redisUrl);
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(uri.getHost(), uri.getPort());
        if (uri.getPassword() != null) {
            config.setPassword(RedisPassword.of(new String(uri.getPassword())));
        }
        config.setDatabase(uri.getDatabase());
        return config;
    }

    private static RedisStandaloneConfiguration fromDiscreteProperties(
            String redisHost, int redisPort, String redisPassword, int redisDatabase) {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(redisHost, redisPort);
        if (StringUtils.hasText(redisPassword)) {
            config.setPassword(RedisPassword.of(redisPassword));
        }
        config.setDatabase(redisDatabase);
        return config;
    }
}

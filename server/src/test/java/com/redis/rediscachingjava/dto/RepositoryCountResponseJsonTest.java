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

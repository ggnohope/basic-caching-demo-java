package com.redis.rediscachingjava.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.redis.rediscachingjava.config.SecurityConfig;
import com.redis.rediscachingjava.dto.RepositoryCountResponse;
import com.redis.rediscachingjava.exception.GitHubUserNotFoundException;
import com.redis.rediscachingjava.service.RepositoryLookupService;

@WebMvcTest(RepositoryController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "app.cors.allowed-origins=*")
class RepositoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RepositoryLookupService lookupService;

    @Test
    void returnsRepositoryCountJsonAndResponseTimeHeader() throws Exception {
        when(lookupService.getRepositoryCount("octocat"))
                .thenReturn(new RepositoryCountResponse("octocat", "8", true));

        mockMvc.perform(get("/repos/octocat").header("Origin", "http://localhost:8081"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("octocat"))
                .andExpect(jsonPath("$.repos").value("8"))
                .andExpect(jsonPath("$.cached").value(true))
                .andExpect(header().exists("X-Response-Time"))
                .andExpect(header().string("Access-Control-Expose-Headers", "X-Response-Time"));
    }

    @Test
    void responseTimeHeaderEndsWithLiteralMs() throws Exception {
        when(lookupService.getRepositoryCount("octocat"))
                .thenReturn(new RepositoryCountResponse("octocat", "8", true));

        mockMvc.perform(get("/repos/octocat"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String headerValue = result.getResponse().getHeader("X-Response-Time");
                    assertThat(headerValue).endsWith("ms");
                });
    }

    @Test
    void returns404WhenUserNotFound() throws Exception {
        when(lookupService.getRepositoryCount("doesnotexist"))
                .thenThrow(new GitHubUserNotFoundException("doesnotexist"));

        mockMvc.perform(get("/repos/doesnotexist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }
}

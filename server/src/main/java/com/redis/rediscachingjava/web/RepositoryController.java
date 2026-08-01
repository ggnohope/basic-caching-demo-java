package com.redis.rediscachingjava.web;

import java.text.DecimalFormat;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.redis.rediscachingjava.dto.RepositoryCountResponse;
import com.redis.rediscachingjava.service.RepositoryLookupService;

import jakarta.servlet.http.HttpServletResponse;

@RestController
public class RepositoryController {

    private final RepositoryLookupService lookupService;

    public RepositoryController(RepositoryLookupService lookupService) {
        this.lookupService = lookupService;
    }

    @GetMapping("/repos/{username}")
    public RepositoryCountResponse getRepositoryCount(
            @PathVariable String username, HttpServletResponse response) {
        long startNanos = System.nanoTime();
        RepositoryCountResponse result = lookupService.getRepositoryCount(username);
        response.addHeader("X-Response-Time", formatElapsedMillis(System.nanoTime() - startNanos));
        return result;
    }

    private static String formatElapsedMillis(long elapsedNanos) {
        DecimalFormat format = new DecimalFormat("#.###");
        return format.format(elapsedNanos / 1_000_000.0) + "ms";
    }
}

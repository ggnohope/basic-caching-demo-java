package com.redis.rediscachingjava.web;

import java.text.DecimalFormat;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.redis.rediscachingjava.dto.RepositoryCountResponse;
import com.redis.rediscachingjava.kafka.RepositoryLookupEvent;
import com.redis.rediscachingjava.kafka.RepositoryLookupEventProducer;
import com.redis.rediscachingjava.service.RepositoryLookupService;

import jakarta.servlet.http.HttpServletResponse;

@RestController
public class RepositoryController {

    private static final Logger log = LoggerFactory.getLogger(RepositoryController.class);

    private final RepositoryLookupService lookupService;
    private final RepositoryLookupEventProducer eventProducer;

    public RepositoryController(
            RepositoryLookupService lookupService, RepositoryLookupEventProducer eventProducer) {
        this.lookupService = lookupService;
        this.eventProducer = eventProducer;
    }

    @GetMapping("/repos/{username}")
    public RepositoryCountResponse getRepositoryCount(
            @PathVariable String username, HttpServletResponse response) {
        long startNanos = System.nanoTime();
        RepositoryCountResponse result = lookupService.getRepositoryCount(username);
        response.addHeader("X-Response-Time", formatElapsedMillis(System.nanoTime() - startNanos));
        publishLookupEvent(result);
        return result;
    }

    private void publishLookupEvent(RepositoryCountResponse result) {
        try {
            eventProducer.publish(new RepositoryLookupEvent(
                    result.username(), result.repos(), result.cached(), Instant.now()));
        } catch (Exception e) {
            log.warn("Failed to publish repository lookup event", e);
        }
    }

    private static String formatElapsedMillis(long elapsedNanos) {
        DecimalFormat format = new DecimalFormat("#.###");
        return format.format(elapsedNanos / 1_000_000.0) + "ms";
    }
}

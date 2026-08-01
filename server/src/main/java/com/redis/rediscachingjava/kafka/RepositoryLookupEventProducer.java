package com.redis.rediscachingjava.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class RepositoryLookupEventProducer {

    private static final String TOPIC = "repository-lookups";

    private static final Logger log = LoggerFactory.getLogger(RepositoryLookupEventProducer.class);

    private final KafkaTemplate<String, RepositoryLookupEvent> kafkaTemplate;

    public RepositoryLookupEventProducer(KafkaTemplate<String, RepositoryLookupEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(RepositoryLookupEvent event) {
        try {
            kafkaTemplate.send(TOPIC, event.username(), event)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.warn("Failed to publish repository lookup event for '{}'", event.username(), ex);
                        }
                    });
        } catch (Exception e) {
            log.warn("Failed to publish repository lookup event for '{}'", event.username(), e);
        }
    }
}

package com.redis.rediscachingjava.kafka;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

@SuppressWarnings("unchecked")
class RepositoryLookupEventProducerTest {

    private KafkaTemplate<String, RepositoryLookupEvent> kafkaTemplate;
    private RepositoryLookupEventProducer producer;

    @BeforeEach
    void setUp() {
        kafkaTemplate = mock(KafkaTemplate.class);
        producer = new RepositoryLookupEventProducer(kafkaTemplate);
    }

    @Test
    void sendsEventToRepositoryLookupsTopicKeyedByUsername() {
        RepositoryLookupEvent event =
                new RepositoryLookupEvent("octocat", "8", true, Instant.parse("2026-08-01T00:00:00Z"));
        SendResult<String, RepositoryLookupEvent> sendResult = mock(SendResult.class);
        when(kafkaTemplate.send("repository-lookups", "octocat", event))
                .thenReturn(CompletableFuture.completedFuture(sendResult));

        producer.publish(event);

        verify(kafkaTemplate).send("repository-lookups", "octocat", event);
    }

    @Test
    void doesNotPropagateWhenKafkaTemplateThrowsSynchronously() {
        RepositoryLookupEvent event =
                new RepositoryLookupEvent("octocat", "8", true, Instant.parse("2026-08-01T00:00:00Z"));
        when(kafkaTemplate.send("repository-lookups", "octocat", event))
                .thenThrow(new RuntimeException("boom"));

        assertThatCode(() -> producer.publish(event)).doesNotThrowAnyException();
    }

    @Test
    void doesNotPropagateWhenFutureCompletesExceptionally() {
        RepositoryLookupEvent event =
                new RepositoryLookupEvent("octocat", "8", true, Instant.parse("2026-08-01T00:00:00Z"));
        CompletableFuture<SendResult<String, RepositoryLookupEvent>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("broker down"));
        when(kafkaTemplate.send("repository-lookups", "octocat", event)).thenReturn(failedFuture);

        assertThatCode(() -> producer.publish(event)).doesNotThrowAnyException();
    }
}

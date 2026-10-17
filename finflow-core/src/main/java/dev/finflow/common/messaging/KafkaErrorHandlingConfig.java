package dev.finflow.common.messaging;

import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration
public class KafkaErrorHandlingConfig {

    private static final String DEAD_LETTER_SUFFIX = ".DLT";
    private static final long MAX_BACKOFF_MILLIS = 30_000;

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template, KafkaRetryProperties retry) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, exception) -> new TopicPartition(record.topic() + DEAD_LETTER_SUFFIX, -1));
        ExponentialBackOff backOff = new ExponentialBackOff(retry.initialInterval().toMillis(), retry.multiplier());
        backOff.setMaxInterval(MAX_BACKOFF_MILLIS);
        backOff.setMaxAttempts(retry.maxRetries());
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(InvalidEventException.class);
        return handler;
    }
}

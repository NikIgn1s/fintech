package dev.finflow.common.outbox;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EventEnvelope(
        UUID eventId,
        String eventType,
        int version,
        Instant occurredAt,
        JsonNode payload) {

    public static final int CURRENT_VERSION = 1;
}

package dev.finflow.common.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record EventEnvelope(
        UUID eventId,
        String eventType,
        int version,
        Instant occurredAt,
        JsonNode payload) {

    public static final int CURRENT_VERSION = 1;
}

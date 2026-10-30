package dev.finflow.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import dev.finflow.common.outbox.EventEnvelope;
import dev.finflow.importing.messaging.TransactionsImported;
import dev.finflow.transfer.domain.FraudSource;
import dev.finflow.transfer.domain.TransferStatus;
import dev.finflow.transfer.messaging.FraudCheckResult;
import dev.finflow.transfer.messaging.TransferCheckRequested;
import dev.finflow.transfer.messaging.TransferStatusChanged;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

class EventContractsTest {

    private static final Path CONTRACTS = Path.of("..", "contracts");

    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json()
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private JsonSchema schema(String name) throws IOException {
        SchemaValidatorsConfig config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(Files.readString(CONTRACTS.resolve(name + ".schema.json")), InputFormat.JSON, config);
    }

    private JsonNode example(String name) throws IOException {
        return mapper.readTree(Files.readString(CONTRACTS.resolve("examples").resolve(name + ".json")));
    }

    private JsonNode envelope(String type, Object payload) {
        return mapper.valueToTree(new EventEnvelope(UUID.randomUUID(), type, EventEnvelope.CURRENT_VERSION,
                Instant.now(), mapper.valueToTree(payload)));
    }

    private boolean valid(String schemaName, JsonNode message) throws IOException {
        return schema(schemaName).validate(message).isEmpty();
    }

    @Test
    void examplesMatchTheirSchemas() throws IOException {
        for (String name : new String[] {"transfer-check-requested", "fraud-check-completed",
                "transfer-status-changed", "transactions-imported", "transactions-categorized", "budget-threshold-exceeded"}) {
            assertThat(schema(name).validate(example(name))).as(name).isEmpty();
        }
    }

    @Test
    void eventsProducedByJavaMatchTheContracts() throws IOException {
        TransferCheckRequested check = new TransferCheckRequested(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), new BigDecimal("15000.0000"), "RUB", 3, true, 0, 7,
                new BigDecimal("48000.0000"), new BigDecimal("2100.0000"), 0, 1, 4, Instant.now());
        TransferStatusChanged changed = new TransferStatusChanged(UUID.randomUUID(), TransferStatus.ON_HOLD,
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("15000.0000"), "RUB",
                FraudSource.ML.name(), Instant.now());

        assertThat(valid("transfer-check-requested", envelope("TransferCheckRequested", check))).isTrue();
        assertThat(valid("transfer-status-changed", envelope("TransferStatusChanged", changed))).isTrue();
        var alert = new dev.finflow.budget.service.BudgetService.Alert(1, UUID.randomUUID(), (short) 1,
                java.time.LocalDate.of(2026, 10, 1), "RUB", 80, "1000.0000", "800.0000");
        assertThat(valid("budget-threshold-exceeded", envelope("BudgetThresholdExceeded", alert))).isTrue();
        TransactionsImported imported = new TransactionsImported(UUID.randomUUID(), UUID.randomUUID(), 10, 1, 0,
                java.time.LocalDate.of(2026, 9, 1), java.time.LocalDate.of(2026, 9, 30));
        assertThat(valid("transactions-imported", envelope("TransactionsImported", imported))).isTrue();
    }

    @Test
    void javaConsumerReadsTheMlExample() throws IOException {
        EventEnvelope envelope = mapper.treeToValue(example("fraud-check-completed"), EventEnvelope.class);
        FraudCheckResult result = mapper.treeToValue(envelope.payload(), FraudCheckResult.class);

        assertThat(envelope.eventType()).isEqualTo("FraudCheckCompleted");
        assertThat(result.decision().name()).isEqualTo("HOLD");
        assertThat(result.score()).isEqualByComparingTo("0.87");
        assertThat(result.reasons()).containsExactly("amount far above average", "new recipient");
    }

    @Test
    void schemasRejectBrokenMessages() throws IOException {
        ObjectNode badScore = (ObjectNode) example("fraud-check-completed");
        ((ObjectNode) badScore.get("payload")).put("score", 1.5);
        ObjectNode badDecision = (ObjectNode) example("fraud-check-completed");
        ((ObjectNode) badDecision.get("payload")).put("decision", "MAYBE");
        ObjectNode noEventId = (ObjectNode) example("fraud-check-completed");
        noEventId.remove("eventId");
        ObjectNode badAmount = (ObjectNode) example("transfer-check-requested");
        ((ObjectNode) badAmount.get("payload")).put("amount", "15000");
        ObjectNode wrongType = (ObjectNode) example("transfer-status-changed");
        wrongType.put("eventType", "SomethingElse");

        assertThat(valid("fraud-check-completed", badScore)).isFalse();
        assertThat(valid("fraud-check-completed", badDecision)).isFalse();
        assertThat(valid("fraud-check-completed", noEventId)).isFalse();
        assertThat(valid("transfer-check-requested", badAmount)).isFalse();
        assertThat(valid("transfer-status-changed", wrongType)).isFalse();
    }
}

package dev.finflow.transfer.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.finflow.common.money.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TransferTest {

    private static final Instant T0 = Instant.parse("2026-10-15T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-10-15T10:00:05Z");

    private Transfer newTransfer() {
        return Transfer.create(1L, 2L, Money.of("1000", "RUB"), "rent", UUID.randomUUID(), T0);
    }

    @Test
    void startsPendingCheckWithTimestamps() {
        Transfer transfer = newTransfer();

        assertThat(transfer.getId()).isNotNull();
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.PENDING_CHECK);
        assertThat(transfer.getAmount()).isEqualByComparingTo("1000");
        assertThat(transfer.getCurrency()).isEqualTo("RUB");
        assertThat(transfer.getCreatedAt()).isEqualTo(T0);
        assertThat(transfer.getUpdatedAt()).isEqualTo(T0);
    }

    @Test
    void rejectsSameSourceAndDestination() {
        assertThatThrownBy(() -> Transfer.create(1L, 1L, Money.of("10", "RUB"), null, UUID.randomUUID(), T0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonPositiveAmount() {
        assertThatThrownBy(() -> Transfer.create(1L, 2L, Money.of("0", "RUB"), null, UUID.randomUUID(), T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Transfer.create(1L, 2L, Money.of("-5", "RUB"), null, UUID.randomUUID(), T0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void completesWithScore() {
        Transfer transfer = newTransfer();

        transfer.complete(new BigDecimal("0.1200"), T1);

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(transfer.getFraudScore()).isEqualByComparingTo("0.12");
        assertThat(transfer.getUpdatedAt()).isEqualTo(T1);
    }

    @Test
    void holdsWithReasonAndScoreThenCanBeApproved() {
        Transfer transfer = newTransfer();

        transfer.hold("night transfer to new recipient", new BigDecimal("0.8700"), T1);
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.ON_HOLD);
        assertThat(transfer.getHoldReason()).isEqualTo("night transfer to new recipient");

        transfer.complete(null, T1);
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(transfer.getFraudScore()).isEqualByComparingTo("0.87");
    }

    @Test
    void heldTransferCanBeRejected() {
        Transfer transfer = newTransfer();
        transfer.hold("suspicious", new BigDecimal("0.9"), T1);

        transfer.reject("confirmed fraud", T1);

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.REJECTED);
        assertThat(transfer.getHoldReason()).isEqualTo("confirmed fraud");
    }

    @Test
    void pendingTransferCannotBeRejectedDirectly() {
        Transfer transfer = newTransfer();

        assertThatThrownBy(() -> transfer.reject("no", T1)).isInstanceOf(IllegalTransferStateException.class);
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.PENDING_CHECK);
    }

    @Test
    void completedTransferIsFinal() {
        Transfer transfer = newTransfer();
        transfer.complete(null, T1);

        assertThatThrownBy(() -> transfer.hold("late", BigDecimal.ONE, T1))
                .isInstanceOf(IllegalTransferStateException.class)
                .hasMessageContaining("COMPLETED");
        assertThatThrownBy(() -> transfer.fail("late", T1)).isInstanceOf(IllegalTransferStateException.class);
        assertThat(transfer.getUpdatedAt()).isEqualTo(T1);
    }

    @Test
    void pendingTransferCanFail() {
        Transfer transfer = newTransfer();

        transfer.fail("ledger unavailable", T1);

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(transfer.getHoldReason()).isEqualTo("ledger unavailable");
    }
}

package dev.finflow.transfer.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class TransferStatusTest {

    @Test
    void pendingCheckCanResolveInThreeWays() {
        assertThat(TransferStatus.PENDING_CHECK.allowedTransitions())
                .containsExactlyInAnyOrder(TransferStatus.COMPLETED, TransferStatus.ON_HOLD, TransferStatus.FAILED);
    }

    @Test
    void onHoldCanBeApprovedOrRejectedOnly() {
        assertThat(TransferStatus.ON_HOLD.allowedTransitions())
                .containsExactlyInAnyOrder(TransferStatus.COMPLETED, TransferStatus.REJECTED);
    }

    @ParameterizedTest
    @EnumSource(value = TransferStatus.class, names = {"COMPLETED", "REJECTED", "FAILED"})
    void terminalStatesAllowNothing(TransferStatus status) {
        assertThat(status.isTerminal()).isTrue();
        for (TransferStatus target : TransferStatus.values()) {
            assertThat(status.canTransitionTo(target)).isFalse();
        }
    }

    @ParameterizedTest
    @EnumSource(TransferStatus.class)
    void noStateTransitionsToItselfOrBackToPending(TransferStatus status) {
        assertThat(status.canTransitionTo(status)).isFalse();
        assertThat(status.canTransitionTo(TransferStatus.PENDING_CHECK)).isFalse();
    }

    @Test
    void nonTerminalStatesAreExactlyPendingAndOnHold() {
        Set<TransferStatus> nonTerminal = Set.of(TransferStatus.PENDING_CHECK, TransferStatus.ON_HOLD);

        for (TransferStatus status : TransferStatus.values()) {
            assertThat(status.isTerminal()).isEqualTo(!nonTerminal.contains(status));
        }
    }
}

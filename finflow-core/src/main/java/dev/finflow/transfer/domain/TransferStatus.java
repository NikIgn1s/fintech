package dev.finflow.transfer.domain;

import java.util.EnumSet;
import java.util.Set;

public enum TransferStatus {
    PENDING_CHECK,
    ON_HOLD,
    COMPLETED,
    REJECTED,
    FAILED;

    public Set<TransferStatus> allowedTransitions() {
        return switch (this) {
            case PENDING_CHECK -> EnumSet.of(COMPLETED, ON_HOLD, FAILED);
            case ON_HOLD -> EnumSet.of(COMPLETED, REJECTED);
            case COMPLETED, REJECTED, FAILED -> EnumSet.noneOf(TransferStatus.class);
        };
    }

    public boolean canTransitionTo(TransferStatus target) {
        return allowedTransitions().contains(target);
    }

    public boolean isTerminal() {
        return allowedTransitions().isEmpty();
    }
}

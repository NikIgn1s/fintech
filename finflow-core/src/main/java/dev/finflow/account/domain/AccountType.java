package dev.finflow.account.domain;

public enum AccountType {
    USER_WALLET,
    USER_SAVINGS,
    SYSTEM_GATEWAY,
    SYSTEM_SUSPENSE;

    public boolean isSystem() {
        return this == SYSTEM_GATEWAY || this == SYSTEM_SUSPENSE;
    }
}

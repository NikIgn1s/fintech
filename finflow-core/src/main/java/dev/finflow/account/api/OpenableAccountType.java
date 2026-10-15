package dev.finflow.account.api;

import dev.finflow.account.domain.AccountType;

public enum OpenableAccountType {
    USER_WALLET(AccountType.USER_WALLET),
    USER_SAVINGS(AccountType.USER_SAVINGS);

    private final AccountType accountType;

    OpenableAccountType(AccountType accountType) {
        this.accountType = accountType;
    }

    public AccountType toAccountType() {
        return accountType;
    }
}

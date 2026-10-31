package dev.finflow.account.api;

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

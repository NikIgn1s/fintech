package dev.finflow.account.api;

public interface SystemAccounts {
    Long gatewayFor(String currency);
    Long suspenseFor(String currency);
}

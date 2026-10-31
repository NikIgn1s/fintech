package dev.finflow.account.api;

import java.util.UUID;

public interface AccountAccess {
    AccountResponse getOwn(UUID userId,Long accountId);
    AccountRef getTransferTarget(Long accountId);
}

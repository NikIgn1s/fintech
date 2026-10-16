package dev.finflow.transfer.api;

import java.util.List;
import java.util.UUID;

public record TransferPage(List<TransferResponse> items, UUID nextCursor) {
}

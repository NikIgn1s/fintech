package dev.finflow.transfer.api;

import java.util.List;

public record AdminTransferPage(
        List<AdminTransferResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages) {
}

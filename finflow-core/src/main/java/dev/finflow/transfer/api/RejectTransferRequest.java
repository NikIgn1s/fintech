package dev.finflow.transfer.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RejectTransferRequest(@NotBlank @Size(max = 255) String reason) {
}

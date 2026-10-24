package dev.finflow.importing.api;

public record ImportErrorItem(int lineNumber, String reason, String rawLine) {
}

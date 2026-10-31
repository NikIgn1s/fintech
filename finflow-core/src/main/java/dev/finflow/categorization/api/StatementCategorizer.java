package dev.finflow.categorization.api;

import java.util.UUID;

public interface StatementCategorizer {
    void categorize(UUID importId,UUID userId);
}

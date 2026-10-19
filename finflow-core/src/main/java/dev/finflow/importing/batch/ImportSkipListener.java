package dev.finflow.importing.batch;

import java.util.UUID;
import org.springframework.batch.core.SkipListener;
import org.springframework.batch.item.file.FlatFileParseException;

public class ImportSkipListener implements SkipListener<ParsedLine, BankTransactionRow> {

    private final UUID importId;
    private final ImportProgress progress;

    public ImportSkipListener(UUID importId, ImportProgress progress) {
        this.importId = importId;
        this.progress = progress;
    }

    @Override
    public void onSkipInRead(Throwable error) {
        if (error instanceof FlatFileParseException parseError) {
            Throwable cause = parseError.getCause() == null ? parseError : parseError.getCause();
            progress.addFailure(importId, parseError.getLineNumber(), cause.getMessage(), parseError.getInput());
        }
    }
}

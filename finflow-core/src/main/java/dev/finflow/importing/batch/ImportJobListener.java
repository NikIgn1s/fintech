package dev.finflow.importing.batch;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ImportJobListener implements JobExecutionListener {

    static final String IMPORT_ID = "importId";

    private final ImportProgress progress;

    @Override
    public void beforeJob(JobExecution execution) {
        progress.markProcessing(importId(execution), execution.getId());
    }

    @Override
    public void afterJob(JobExecution execution) {
        if (execution.getStatus() == BatchStatus.COMPLETED) {
            return;
        }
        progress.fail(importId(execution), failureReason(execution));
    }

    private UUID importId(JobExecution execution) {
        return UUID.fromString(execution.getJobParameters().getString(IMPORT_ID));
    }

    private String failureReason(JobExecution execution) {
        return execution.getAllFailureExceptions().stream().findFirst().map(error -> {
            Throwable root = error;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            return root.getMessage() == null ? root.toString() : root.getMessage();
        }).orElse("Import failed with status " + execution.getStatus());
    }
}

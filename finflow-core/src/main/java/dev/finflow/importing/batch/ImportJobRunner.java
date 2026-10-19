package dev.finflow.importing.batch;

import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ImportJobRunner {

    private final JobLauncher launcher;
    private final Job job;
    private final ImportProgress progress;
    private final TaskExecutor executor;

    public ImportJobRunner(JobLauncher launcher, Job statementImportJob, ImportProgress progress,
            TaskExecutor importExecutor) {
        this.launcher = launcher;
        this.job = statementImportJob;
        this.progress = progress;
        this.executor = importExecutor;
    }

    public JobExecution run(UUID importId) throws Exception {
        ImportProgress.ImportInfo info = progress.find(importId);
        JobParameters parameters = new JobParametersBuilder()
                .addString(ImportJobListener.IMPORT_ID, importId.toString(), true)
                .addString("userId", info.userId().toString(), false)
                .addString("bankFormat", info.format().name(), false)
                .addString("fileKey", info.fileKey(), false)
                .toJobParameters();
        return launcher.run(job, parameters);
    }

    public void runAsync(UUID importId) {
        executor.execute(() -> {
            try {
                run(importId);
            } catch (Exception e) {
                log.error("Import {} could not be started", importId, e);
                progress.fail(importId, "Import could not be started: " + e.getMessage());
            }
        });
    }
}

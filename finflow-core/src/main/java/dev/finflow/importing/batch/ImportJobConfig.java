package dev.finflow.importing.batch;

import dev.finflow.importing.parser.BankFormat;
import dev.finflow.importing.parser.BankStatementParser;
import dev.finflow.importing.parser.DescriptionNormalizer;
import dev.finflow.importing.parser.StatementParsers;
import dev.finflow.importing.storage.StatementStorage;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.FlatFileParseException;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@RequiredArgsConstructor
public class ImportJobConfig {

    public static final String JOB_NAME = "statementImportJob";
    static final int CHUNK_SIZE = 500;
    static final int SKIP_LIMIT = 100;

    private final StatementParsers parsers;
    private final StatementStorage storage;
    private final ImportProgress progress;
    private final DescriptionNormalizer normalizer;

    @Bean
    Job statementImportJob(JobRepository repository, Step parseStep, Step categorizeStep, Step finalizeStep,
            ImportJobListener listener) {
        return new JobBuilder(JOB_NAME, repository)
                .listener(listener)
                .start(parseStep)
                .next(categorizeStep)
                .next(finalizeStep)
                .build();
    }

    @Bean
    Step parseStep(JobRepository repository, PlatformTransactionManager transactions,
            FlatFileItemReader<ParsedLine> statementReader,
            ItemProcessor<ParsedLine, BankTransactionRow> statementProcessor, BankTransactionWriter writer,
            ImportSkipListener skipListener) {
        return new StepBuilder("parseStep", repository)
                .<ParsedLine, BankTransactionRow>chunk(CHUNK_SIZE, transactions)
                .reader(statementReader)
                .processor(statementProcessor)
                .writer(writer)
                .faultTolerant()
                .skip(FlatFileParseException.class)
                .skipLimit(SKIP_LIMIT)
                .listener(skipListener)
                .build();
    }

    @Bean
    Step categorizeStep(JobRepository repository, PlatformTransactionManager transactions,
            Tasklet categorizeTasklet) {
        return new StepBuilder("categorizeStep", repository).tasklet(categorizeTasklet, transactions).build();
    }

    @Bean
    @StepScope
    Tasklet categorizeTasklet(@Value("#{jobParameters['importId']}") String importId,
            @Value("#{jobParameters['userId']}") String userId,
            dev.finflow.categorization.service.CategorizationService service) {
        return (contribution, context) -> {
            service.categorize(UUID.fromString(importId), UUID.fromString(userId));
            return RepeatStatus.FINISHED;
        };
    }

    @Bean
    Step finalizeStep(JobRepository repository, PlatformTransactionManager transactions, Tasklet finalizeTasklet) {
        return new StepBuilder("finalizeStep", repository)
                .tasklet(finalizeTasklet, transactions)
                .build();
    }

    @Bean
    @StepScope
    FlatFileItemReader<ParsedLine> statementReader(@Value("#{jobParameters['fileKey']}") String fileKey,
            @Value("#{jobParameters['bankFormat']}") String bankFormat) {
        BankStatementParser parser = parsers.forFormat(BankFormat.valueOf(bankFormat));
        return new FlatFileItemReaderBuilder<ParsedLine>()
                .name("statementReader")
                .resource(new InputStreamResource(storage.open(fileKey)))
                .encoding(parser.charset().name())
                .linesToSkip(1)
                .skippedLinesCallback(parser::validateHeader)
                .lineMapper((line, number) -> line.isBlank()
                        ? ParsedLine.blank(number)
                        : new ParsedLine(number, parser.parseLine(line)))
                .build();
    }

    @Bean
    @StepScope
    ItemProcessor<ParsedLine, BankTransactionRow> statementProcessor(
            @Value("#{jobParameters['userId']}") String userId,
            @Value("#{jobParameters['importId']}") String importId) {
        return new StatementItemProcessor(UUID.fromString(userId), UUID.fromString(importId), normalizer);
    }

    @Bean
    @StepScope
    ImportSkipListener importSkipListener(@Value("#{jobParameters['importId']}") String importId) {
        return new ImportSkipListener(UUID.fromString(importId), progress);
    }

    @Bean
    @StepScope
    Tasklet finalizeTasklet(@Value("#{jobParameters['importId']}") String importId,
            @Value("#{jobParameters['userId']}") String userId, ImportFinalizer finalizer) {
        return (contribution, context) -> {
            finalizer.finish(UUID.fromString(importId), UUID.fromString(userId));
            return RepeatStatus.FINISHED;
        };
    }

    @Bean
    TaskExecutor importExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("import-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        return executor;
    }
}

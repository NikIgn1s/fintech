package dev.finflow.importing.service;

import dev.finflow.common.error.ApiException;
import dev.finflow.common.error.BusinessRuleException;
import dev.finflow.common.error.NotFoundException;
import dev.finflow.common.id.UuidV7;
import dev.finflow.importing.api.ImportErrorItem;
import dev.finflow.importing.api.ImportResponse;
import dev.finflow.importing.batch.ImportJobRunner;
import dev.finflow.importing.domain.StatementImport;
import dev.finflow.importing.parser.BankFormat;
import dev.finflow.importing.parser.BankStatementParser;
import dev.finflow.importing.parser.StatementParseException;
import dev.finflow.importing.parser.StatementParsers;
import dev.finflow.importing.repository.StatementImportRepository;
import dev.finflow.importing.storage.StatementStorage;
import java.io.IOException;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImportService {

    public static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    private static final int ERRORS_LIMIT = 100;

    private final StatementParsers parsers;
    private final StatementStorage storage;
    private final StatementImportRepository imports;
    private final ImportJobRunner runner;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ImportResponse upload(UUID userId, BankFormat format, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessRuleException("empty-file", "Empty file", "The uploaded file is empty");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "file-too-large", "File too large",
                    "The file must not exceed " + MAX_FILE_BYTES / (1024 * 1024) + " MB");
        }
        byte[] content = read(file);
        validate(format, content);

        UUID importId = UuidV7.generate();
        String key = storage.store(userId, importId, file.getOriginalFilename(), content, file.getContentType());
        StatementImport saved;
        try {
            saved = imports.save(StatementImport.uploaded(importId, userId, format, key,
                    file.getOriginalFilename(), clock.instant()));
        } catch (RuntimeException e) {
            deleteQuietly(key);
            throw e;
        }
        runner.runAsync(importId);
        return ImportResponse.of(saved, null);
    }

    @Transactional(readOnly = true)
    public ImportResponse get(UUID userId, UUID importId) {
        StatementImport statementImport = imports.findByIdAndUserId(importId, userId)
                .orElseThrow(() -> new NotFoundException("Import " + importId + " not found"));
        List<ImportErrorItem> errors = jdbc.query("select line_number, reason, raw_line from import_errors "
                + "where import_id = ? order by line_number, id limit ?",
                (rs, row) -> new ImportErrorItem(rs.getInt("line_number"), rs.getString("reason"),
                        rs.getString("raw_line")), importId, ERRORS_LIMIT);
        return ImportResponse.of(statementImport, errors);
    }

    private byte[] read(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "unreadable-file", "Unreadable file",
                    "The uploaded file could not be read");
        }
    }

    private void validate(BankFormat format, byte[] content) {
        BankStatementParser parser = parsers.forFormat(format);
        String[] lines = new String(content, parser.charset()).split("\\R", -1);
        try {
            parser.validateHeader(lines[0]);
        } catch (StatementParseException e) {
            throw new BusinessRuleException("invalid-statement", "Invalid statement", e.getMessage());
        }
        if (Arrays.stream(lines).skip(1).allMatch(String::isBlank)) {
            throw new BusinessRuleException("empty-statement", "Empty statement",
                    "The statement has a header but no transactions");
        }
    }

    private void deleteQuietly(String key) {
        try {
            storage.delete(key);
        } catch (RuntimeException e) {
            log.warn("Could not remove orphaned statement file {}", key, e);
        }
    }
}

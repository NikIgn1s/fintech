package dev.finflow.importing.parser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StatementFileParser {

    public record Row(int lineNumber, ParsedTransaction transaction) {
    }

    public record Failure(int lineNumber, String reason, String rawLine) {
    }

    public record Result(List<Row> rows, List<Failure> failures) {
    }

    private final StatementParsers parsers;

    public Result parse(InputStream content, BankFormat format) throws IOException {
        BankStatementParser parser = parsers.forFormat(format);
        List<Row> rows = new ArrayList<>();
        List<Failure> failures = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(content, parser.charset()))) {
            String header = reader.readLine();
            if (header == null) {
                throw new StatementParseException("File is empty");
            }
            parser.validateHeader(header);
            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                try {
                    rows.add(new Row(lineNumber, parser.parseLine(line)));
                } catch (StatementParseException e) {
                    failures.add(new Failure(lineNumber, e.getMessage(), line));
                }
            }
        }
        return new Result(rows, failures);
    }
}

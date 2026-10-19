package dev.finflow.importing.parser;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class StatementParsers {

    private final Map<BankFormat, BankStatementParser> byFormat = new EnumMap<>(BankFormat.class);

    public StatementParsers(List<BankStatementParser> parsers) {
        parsers.forEach(parser -> byFormat.put(parser.format(), parser));
    }

    public BankStatementParser forFormat(BankFormat format) {
        BankStatementParser parser = byFormat.get(format);
        if (parser == null) {
            throw new IllegalArgumentException("No parser registered for " + format);
        }
        return parser;
    }
}

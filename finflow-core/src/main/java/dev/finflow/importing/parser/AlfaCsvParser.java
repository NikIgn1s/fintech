package dev.finflow.importing.parser;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class AlfaCsvParser extends DelimitedStatementParser {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yy");
    private static final String DEBIT = "списание";
    private static final String CREDIT = "пополнение";

    public AlfaCsvParser() {
        super('\t', List.of("Дата", "Контрагент", "Сумма", "Тип", "Валюта"));
    }

    @Override
    public BankFormat format() {
        return BankFormat.ALFA;
    }

    @Override
    public Charset charset() {
        return StandardCharsets.UTF_8;
    }

    @Override
    ParsedTransaction toTransaction(List<String> cells) {
        BigDecimal magnitude = parseAmount(cells.get(2)).abs();
        BigDecimal amount = switch (cells.get(3).toLowerCase(Locale.ROOT)) {
            case DEBIT -> magnitude.negate();
            case CREDIT -> magnitude;
            default -> throw new StatementParseException("Unknown operation type '" + cells.get(3) + "'");
        };
        return new ParsedTransaction(parseDate(cells.get(0), DATE), amount, parseCurrency(cells.get(4)),
                parseDescription(cells.get(1)), null);
    }
}

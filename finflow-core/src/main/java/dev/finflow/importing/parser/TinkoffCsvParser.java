package dev.finflow.importing.parser;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class TinkoffCsvParser extends DelimitedStatementParser {

    private static final int DATE_LENGTH = 10;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    public TinkoffCsvParser() {
        super(',', List.of("date", "description", "amount", "currency", "mcc"));
    }

    @Override
    public BankFormat format() {
        return BankFormat.TINKOFF;
    }

    @Override
    public Charset charset() {
        return StandardCharsets.UTF_8;
    }

    @Override
    ParsedTransaction toTransaction(List<String> cells) {
        String date = cells.get(0);
        String dayOnly = date.length() > DATE_LENGTH ? date.substring(0, DATE_LENGTH) : date;
        return new ParsedTransaction(parseDate(dayOnly, DATE), parseAmount(cells.get(2)),
                parseCurrency(cells.get(3)), parseDescription(cells.get(1)), parseMcc(cells.get(4)));
    }
}

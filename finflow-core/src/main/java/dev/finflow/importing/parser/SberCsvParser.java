package dev.finflow.importing.parser;

import java.nio.charset.Charset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class SberCsvParser extends DelimitedStatementParser {

    private static final Charset WINDOWS_1251 = Charset.forName("windows-1251");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    public SberCsvParser() {
        super(';', List.of("Дата операции", "Описание", "Сумма", "Валюта", "MCC"));
    }

    @Override
    public BankFormat format() {
        return BankFormat.SBER;
    }

    @Override
    public Charset charset() {
        return WINDOWS_1251;
    }

    @Override
    ParsedTransaction toTransaction(List<String> cells) {
        return new ParsedTransaction(parseDate(cells.get(0), DATE), parseAmount(cells.get(2)),
                parseCurrency(cells.get(3)), parseDescription(cells.get(1)), parseMcc(cells.get(4)));
    }
}

package dev.finflow.importing.parser;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

abstract class DelimitedStatementParser implements BankStatementParser {

    private static final int MAX_DESCRIPTION_LENGTH = 500;
    private static final int MAX_DECIMALS = 4;

    private final char delimiter;
    private final List<String> expectedHeader;

    DelimitedStatementParser(char delimiter, List<String> expectedHeader) {
        this.delimiter = delimiter;
        this.expectedHeader = expectedHeader;
    }

    @Override
    public void validateHeader(String headerLine) {
        String cleaned = headerLine.startsWith("﻿") ? headerLine.substring(1) : headerLine;
        List<String> actual = CsvLine.split(cleaned, delimiter).stream()
                .map(cell -> cell.toLowerCase(Locale.ROOT)).toList();
        List<String> expected = expectedHeader.stream().map(cell -> cell.toLowerCase(Locale.ROOT)).toList();
        if (!actual.equals(expected)) {
            throw new StatementParseException("Unexpected header for " + format() + ", expected "
                    + String.join(String.valueOf(delimiter), expectedHeader));
        }
    }

    @Override
    public ParsedTransaction parseLine(String line) {
        List<String> cells = CsvLine.split(line, delimiter);
        if (cells.size() != expectedHeader.size()) {
            throw new StatementParseException("Expected " + expectedHeader.size() + " columns but found "
                    + cells.size());
        }
        return toTransaction(cells);
    }

    abstract ParsedTransaction toTransaction(List<String> cells);

    static LocalDate parseDate(String value, DateTimeFormatter formatter) {
        try {
            return LocalDate.parse(value, formatter);
        } catch (DateTimeParseException e) {
            throw new StatementParseException("Invalid date '" + value + "'");
        }
    }

    static BigDecimal parseAmount(String value) {
        String normalized = value.replace(' ', ' ').replace(" ", "").replace('−', '-').replace(',', '.');
        if (normalized.isEmpty()) {
            throw new StatementParseException("Amount is empty");
        }
        try {
            BigDecimal amount = new BigDecimal(normalized);
            if (amount.stripTrailingZeros().scale() > MAX_DECIMALS) {
                throw new StatementParseException("Amount '" + value + "' has too many decimal places");
            }
            return amount.setScale(MAX_DECIMALS);
        } catch (NumberFormatException e) {
            throw new StatementParseException("Invalid amount '" + value + "'");
        }
    }

    static String parseCurrency(String value) {
        String currency = value.trim().toUpperCase(Locale.ROOT);
        if ("RUR".equals(currency)) {
            return "RUB";
        }
        if (!currency.matches("[A-Z]{3}")) {
            throw new StatementParseException("Invalid currency '" + value + "'");
        }
        return currency;
    }

    static String parseDescription(String value) {
        if (value.isBlank()) {
            throw new StatementParseException("Description is empty");
        }
        return value.length() <= MAX_DESCRIPTION_LENGTH ? value : value.substring(0, MAX_DESCRIPTION_LENGTH);
    }

    static String parseMcc(String value) {
        return value.matches("\\d{4}") ? value : null;
    }
}

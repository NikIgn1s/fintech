package dev.finflow.importing.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class StatementParsersTest {

    private static final Charset WINDOWS_1251 = Charset.forName("windows-1251");

    private final StatementFileParser files = new StatementFileParser(new StatementParsers(List.of(
            new SberCsvParser(), new TinkoffCsvParser(), new AlfaCsvParser())));

    private StatementFileParser.Result parse(String content, Charset charset, BankFormat format)
            throws IOException {
        return files.parse(new ByteArrayInputStream(content.getBytes(charset)), format);
    }

    @Test
    void parsesSberFileInWindows1251() throws IOException {
        String csv = """
                Дата операции;Описание;Сумма;Валюта;MCC
                14.10.2026;PYATEROCHKA 1234 MOSCOW RUS;-540,20;RUR;5411
                15.10.2026;Зарплата;85000,00;RUB;
                """;

        var result = parse(csv, WINDOWS_1251, BankFormat.SBER);

        assertThat(result.failures()).isEmpty();
        assertThat(result.rows()).hasSize(2);
        ParsedTransaction first = result.rows().get(0).transaction();
        assertThat(first.operationDate()).isEqualTo(LocalDate.of(2026, 10, 14));
        assertThat(first.amount()).isEqualByComparingTo("-540.20");
        assertThat(first.currency()).isEqualTo("RUB");
        assertThat(first.mcc()).isEqualTo("5411");
        ParsedTransaction second = result.rows().get(1).transaction();
        assertThat(second.description()).isEqualTo("Зарплата");
        assertThat(second.amount()).isEqualByComparingTo("85000");
        assertThat(second.mcc()).isNull();
    }

    @Test
    void parsesTinkoffFileWithQuotesAndDateTime() throws IOException {
        String csv = "date,description,amount,currency,mcc\n"
                + "2026-10-14T13:05:00,\"YANDEX TAXI*SPB, trip\",-312.50,RUB,4121\n"
                + "2026-10-15,\"Cafe \"\"Pushkin\"\"\",-1250,RUB,5814\n";

        var result = parse(csv, StandardCharsets.UTF_8, BankFormat.TINKOFF);

        assertThat(result.failures()).isEmpty();
        assertThat(result.rows().get(0).transaction().description()).isEqualTo("YANDEX TAXI*SPB, trip");
        assertThat(result.rows().get(0).transaction().operationDate()).isEqualTo(LocalDate.of(2026, 10, 14));
        assertThat(result.rows().get(1).transaction().description()).isEqualTo("Cafe \"Pushkin\"");
    }

    @Test
    void parsesAlfaFileWithSeparateDirectionColumnAndThousandsSeparators() throws IOException {
        String csv = "﻿Дата\tКонтрагент\tСумма\tТип\tВалюта\n"
                + "14.10.26\tMTS\t1 234,50\tСписание\tRUB\n"
                + "15.10.26\tИП Иванов\t10 000,00\tПополнение\tRUB\n";

        var result = parse(csv, StandardCharsets.UTF_8, BankFormat.ALFA);

        assertThat(result.failures()).isEmpty();
        assertThat(result.rows().get(0).transaction().amount()).isEqualByComparingTo("-1234.50");
        assertThat(result.rows().get(0).transaction().operationDate()).isEqualTo(LocalDate.of(2026, 10, 14));
        assertThat(result.rows().get(1).transaction().amount()).isEqualByComparingTo("10000");
    }

    @Test
    void collectsBadLinesWithNumbersAndKeepsGoodOnes() throws IOException {
        String csv = """
                date,description,amount,currency,mcc
                2026-10-14,Shop,-10,RUB,
                14/10/2026,Shop,-10,RUB,
                2026-10-14,Shop,abc,RUB,
                2026-10-14,,-5,RUB,
                2026-10-14,Shop,-5,RU,

                2026-10-14,Shop,-1.23456,RUB,
                2026-10-14,only,three
                2026-10-16,Last,-7,USD,
                """;

        var result = parse(csv, StandardCharsets.UTF_8, BankFormat.TINKOFF);

        assertThat(result.rows()).extracting(StatementFileParser.Row::lineNumber).containsExactly(2, 10);
        assertThat(result.failures()).extracting(StatementFileParser.Failure::lineNumber)
                .containsExactly(3, 4, 5, 6, 8, 9);
        assertThat(result.failures().get(0).reason()).contains("Invalid date");
        assertThat(result.failures().get(1).reason()).contains("Invalid amount");
        assertThat(result.failures().get(2).reason()).contains("Description is empty");
        assertThat(result.failures().get(3).reason()).contains("Invalid currency");
        assertThat(result.failures().get(4).reason()).contains("too many decimal places");
        assertThat(result.failures().get(5).reason()).contains("columns");
        assertThat(result.failures().get(0).rawLine()).isEqualTo("14/10/2026,Shop,-10,RUB,");
    }

    @Test
    void rejectsFileWithWrongHeaderOrNoContent() {
        assertThatThrownBy(() -> parse("a,b,c\n1,2,3\n", StandardCharsets.UTF_8, BankFormat.TINKOFF))
                .isInstanceOf(StatementParseException.class).hasMessageContaining("Unexpected header");
        assertThatThrownBy(() -> parse("", StandardCharsets.UTF_8, BankFormat.SBER))
                .isInstanceOf(StatementParseException.class).hasMessageContaining("empty");
    }

    @Test
    void rejectsUnknownOperationTypeAndUnterminatedQuote() throws IOException {
        String alfa = "Дата\tКонтрагент\tСумма\tТип\tВалюта\n14.10.26\tShop\t5\tПеревод\tRUB\n";
        String tinkoff = "date,description,amount,currency,mcc\n2026-10-14,\"broken,-5,RUB,\n";

        assertThat(parse(alfa, StandardCharsets.UTF_8, BankFormat.ALFA).failures().get(0).reason())
                .contains("Unknown operation type");
        assertThat(parse(tinkoff, StandardCharsets.UTF_8, BankFormat.TINKOFF).failures().get(0).reason())
                .contains("Unterminated");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "PYATEROCHKA 1234 MOSCOW RUS|PYATEROCHKA",
            "YANDEX TAXI*SPB|YANDEX TAXI",
            "pyaterochka #55 spb|PYATEROCHKA",
            "OOO AGROTORG 77|AGROTORG",
            "PAYMENT NETFLIX|NETFLIX",
            "LUKOIL AZS 55 KAZAN|LUKOIL AZS",
            "APTEKA 36.6 MOSCOW|APTEKA",
            "Пятерочка 1234 МОСКВА|ПЯТЕРОЧКА",
            "S7 AIRLINES 0123|S7 AIRLINES",
            "   KFC   |KFC",
            "*TIPS|*TIPS"})
    void normalizesDescriptionsToMerchantNames(String raw, String expected) {
        assertThat(new DescriptionNormalizer().merchant(raw)).isEqualTo(expected);
    }
}

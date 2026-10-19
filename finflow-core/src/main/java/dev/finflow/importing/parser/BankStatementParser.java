package dev.finflow.importing.parser;

import java.nio.charset.Charset;

public interface BankStatementParser {

    BankFormat format();

    Charset charset();

    void validateHeader(String headerLine);

    ParsedTransaction parseLine(String line);
}

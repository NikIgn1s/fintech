package dev.finflow.importing.batch;

import dev.finflow.importing.parser.ParsedTransaction;

public record ParsedLine(int lineNumber, ParsedTransaction transaction) {

    static ParsedLine blank(int lineNumber) {
        return new ParsedLine(lineNumber, null);
    }
}

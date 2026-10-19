package dev.finflow.importing.parser;

import java.util.ArrayList;
import java.util.List;

final class CsvLine {

    private CsvLine() {
    }

    static List<String> split(String line, char delimiter) {
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == delimiter) {
                cells.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (quoted) {
            throw new StatementParseException("Unterminated quoted field");
        }
        cells.add(current.toString().trim());
        return cells;
    }
}

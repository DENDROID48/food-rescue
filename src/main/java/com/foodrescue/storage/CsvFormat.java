package com.foodrescue.storage;

import com.foodrescue.exception.DataFileException;

import java.util.ArrayList;
import java.util.List;

/**
 * Writes and reads CSV text (comma separated values, the format spreadsheets open).
 *
 * <p>A value containing a comma, a quote or a line break is wrapped in double quotes, and a
 * quote inside it is doubled: {@code He said "hi", then left} becomes
 * {@code "He said ""hi"", then left"}. A missing value (null) is written as an empty field.</p>
 */
public final class CsvFormat {

    private CsvFormat() {
        // Utility class, no instances.
    }

    /** One record read from a file, with the line it starts on (counting from 1). */
    public record Row(int line, List<String> values) {
    }

    /** One line of CSV, ending in a line break. */
    public static String line(List<?> values) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            Object value = values.get(i);
            out.append(value == null ? "" : escape(value.toString()));
        }
        return out.append('\n').toString();
    }

    private static String escape(String value) {
        boolean needsQuotes = value.contains(",") || value.contains("\"")
                || value.contains("\n") || value.contains("\r")
                || !value.equals(value.strip());
        return needsQuotes ? '"' + value.replace("\"", "\"\"") + '"' : value;
    }

    /**
     * Splits CSV text into records. Blank lines are skipped. A quoted value may span several lines.
     *
     * @param fileName only used in error messages
     * @throws DataFileException when a quoted value is never closed
     */
    public static List<Row> parse(String text, String fileName) {
        if (text.startsWith("﻿")) {
            text = text.substring(1); // Byte order mark that some spreadsheet programs add.
        }
        List<Row> rows = new ArrayList<>();
        List<String> values = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean inQuotes = false;
        boolean rowHasContent = false;
        int line = 1;
        int rowStart = 1;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    value.append('"');
                    i++;
                } else if (c == '"') {
                    inQuotes = false;
                } else {
                    if (c == '\n') {
                        line++;
                    }
                    value.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
                rowHasContent = true;
            } else if (c == ',') {
                values.add(value.toString());
                value.setLength(0);
                rowHasContent = true;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                if (rowHasContent || !value.isEmpty()) {
                    values.add(value.toString());
                    rows.add(new Row(rowStart, List.copyOf(values)));
                }
                values.clear();
                value.setLength(0);
                rowHasContent = false;
                line++;
                rowStart = line;
            } else {
                value.append(c);
            }
        }
        if (inQuotes) {
            throw new DataFileException(fileName, rowStart, "a quoted value is never closed");
        }
        if (rowHasContent || !value.isEmpty()) {
            values.add(value.toString());
            rows.add(new Row(rowStart, List.copyOf(values)));
        }
        return rows;
    }
}

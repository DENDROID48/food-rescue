package com.foodrescue.storage;

import com.foodrescue.exception.DataFileException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * One line of a data file, read by column name. Every getter checks the value and, when it is
 * missing or malformed, throws a {@link DataFileException} naming the file, line and column.
 * This is the validation of everything that enters the program from a file.
 */
final class CsvRecord {

    private final String fileName;
    private final CsvFormat.Row row;
    private final Map<String, Integer> columns;

    CsvRecord(String fileName, CsvFormat.Row row, Map<String, Integer> columns) {
        this.fileName = fileName;
        this.row = row;
        this.columns = columns;
        if (row.values().size() != columns.size()) {
            throw error("expected " + columns.size() + " values but found " + row.values().size());
        }
    }

    /** Raw value, or null when the field is empty. */
    String optionalText(String column) {
        String value = row.values().get(columns.get(column));
        return value.isEmpty() ? null : value;
    }

    String text(String column, int maxLength) {
        String value = optionalText(column);
        if (value == null || value.isBlank()) {
            throw error("'" + column + "' is empty");
        }
        if (value.length() > maxLength) {
            throw error("'" + column + "' is longer than " + maxLength + " characters");
        }
        return value;
    }

    long id(String column) {
        long value = longValue(column);
        if (value < 1) {
            throw error("'" + column + "' must be a positive id, not " + value);
        }
        return value;
    }

    Long optionalLong(String column) {
        return optionalText(column) == null ? null : longValue(column);
    }

    private long longValue(String column) {
        String value = text(column, 20);
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            throw error("'" + column + "' must be a whole number, not '" + value + "'", ex);
        }
    }

    int count(String column) {
        String value = text(column, 10);
        try {
            int number = Integer.parseInt(value);
            if (number < 0) {
                throw error("'" + column + "' cannot be negative");
            }
            return number;
        } catch (NumberFormatException ex) {
            throw error("'" + column + "' must be a whole number, not '" + value + "'", ex);
        }
    }

    double coordinate(String column, double limit) {
        String value = text(column, 30);
        double number;
        try {
            number = Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            throw error("'" + column + "' must be a number, not '" + value + "'", ex);
        }
        if (Double.isNaN(number) || Math.abs(number) > limit) {
            throw error("'" + column + "' must be between -" + limit + " and " + limit);
        }
        return number;
    }

    BigDecimal money(String column) {
        String value = text(column, 20);
        try {
            BigDecimal amount = new BigDecimal(value);
            if (amount.signum() < 0 || amount.scale() > 2) {
                throw error("'" + column + "' must be a positive amount with at most 2 decimals, not " + value);
            }
            return amount;
        } catch (NumberFormatException ex) {
            throw error("'" + column + "' must be an amount such as 12.50, not '" + value + "'", ex);
        }
    }

    Instant instant(String column) {
        String value = text(column, 40);
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ex) {
            throw error("'" + column + "' must be a moment such as 2026-11-13T10:15:30Z, not '" + value + "'", ex);
        }
    }

    boolean bool(String column) {
        String value = text(column, 5);
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            default -> throw error("'" + column + "' must be true or false, not '" + value + "'");
        };
    }

    <E extends Enum<E>> E enumValue(String column, Class<E> type) {
        String value = text(column, 40);
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException ex) {
            throw error("'" + column + "' must be one of " + List.of(type.getEnumConstants()) + ", not '" + value + "'", ex);
        }
    }

    DataFileException error(String message) {
        return new DataFileException(fileName, row.line(), message);
    }

    private DataFileException error(String message, Throwable cause) {
        return new DataFileException(fileName, row.line(), message, cause);
    }
}

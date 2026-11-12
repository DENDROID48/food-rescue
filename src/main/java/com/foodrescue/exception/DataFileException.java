package com.foodrescue.exception;

/**
 * A saved data file cannot be read back: a value has the wrong format, a column is missing,
 * or rows point to things that do not exist. The message names the file and line, so the
 * file can be fixed by hand.
 *
 * <p>Loading stops at the first bad value instead of skipping it: starting with half the data
 * and then saving over the files would lose the rest.</p>
 */
public class DataFileException extends RuntimeException {

    private final String fileName;
    private final int line;

    public DataFileException(String fileName, int line, String message, Throwable cause) {
        super(fileName + (line > 0 ? ", line " + line : "") + ": " + message, cause);
        this.fileName = fileName;
        this.line = line;
    }

    public DataFileException(String fileName, int line, String message) {
        this(fileName, line, message, null);
    }

    public String getFileName() {
        return fileName;
    }

    /** Line in the file, counting from 1, or 0 when the problem is not on one line. */
    public int getLine() {
        return line;
    }
}

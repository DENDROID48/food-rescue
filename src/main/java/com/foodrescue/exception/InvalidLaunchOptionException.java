package com.foodrescue.exception;

/**
 * A program argument such as {@code --mode=} or {@code --port=} has a value the application
 * cannot use. Thrown before Spring starts, so the user gets one clear line instead of a stack trace.
 */
public class InvalidLaunchOptionException extends RuntimeException {

    public InvalidLaunchOptionException(String message) {
        super(message);
    }

    public InvalidLaunchOptionException(String message, Throwable cause) {
        super(message, cause);
    }
}

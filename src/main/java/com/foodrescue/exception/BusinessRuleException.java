package com.foodrescue.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when a request is well formed but breaks a business rule (sold out, expired,
 * daily limit reached, not eligible). Carries the HTTP status that best describes the problem.
 */
public class BusinessRuleException extends RuntimeException {

    private final HttpStatus status;

    public BusinessRuleException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}

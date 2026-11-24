package com.foodrescue.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/** The two custom exceptions keep the information the error response is built from. */
class ExceptionConstructorsTest {

    @Test
    void businessRuleExceptionKeepsItsStatusAndMessage() {
        BusinessRuleException e = new BusinessRuleException(HttpStatus.CONFLICT, "Only 1 unit(s) left");
        assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(e.getMessage()).isEqualTo("Only 1 unit(s) left");
        assertThat(e).isInstanceOf(RuntimeException.class);
    }

    @Test
    void resourceNotFoundExceptionBuildsAReadableMessage() {
        ResourceNotFoundException e = new ResourceNotFoundException("Store", 7L);
        assertThat(e.getMessage()).isEqualTo("Store with id 7 was not found");
        assertThat(e).isInstanceOf(RuntimeException.class);
    }
}

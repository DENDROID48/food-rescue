package com.foodrescue.dto;

import jakarta.validation.constraints.NotNull;

/** Admin decision on a verification request: {@code {"approved": true}}. */
public record EligibilityDecisionRequest(@NotNull Boolean approved) {
}

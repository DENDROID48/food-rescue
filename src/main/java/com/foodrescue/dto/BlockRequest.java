package com.foodrescue.dto;

import jakarta.validation.constraints.Size;

/** Why an account is blocked: optional, for other administrators only. */
public record BlockRequest(@Size(max = 300) String reason) {
}

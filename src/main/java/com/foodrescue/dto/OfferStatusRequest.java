package com.foodrescue.dto;

import com.foodrescue.domain.OfferStatus;
import jakarta.validation.constraints.NotNull;

/** ACTIVE publishes a draft or resumes a paused offer; PAUSED hides an offer. */
public record OfferStatusRequest(@NotNull OfferStatus status) {
}

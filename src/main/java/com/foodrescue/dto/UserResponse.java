package com.foodrescue.dto;

import com.foodrescue.domain.EligibilityStatus;
import com.foodrescue.domain.User;
import com.foodrescue.domain.UserRole;

import java.time.Instant;

/** Public view of an account. The password hash is never included. */
public record UserResponse(
        Long id,
        String fullName,
        String email,
        UserRole role,
        EligibilityStatus eligibilityStatus,
        boolean freeClaimEligible,
        Instant createdAt) {

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                user.getRole(),
                user.getEligibilityStatus(),
                user.isFreeClaimEligible(),
                user.getCreatedAt());
    }
}

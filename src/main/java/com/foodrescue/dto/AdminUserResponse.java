package com.foodrescue.dto;

import com.foodrescue.domain.EligibilityStatus;
import com.foodrescue.domain.User;
import com.foodrescue.domain.UserRole;

import java.time.Instant;

/**
 * A person as administrators see them in the People list.
 *
 * @param noShows       missed pickups within the no-show window
 * @param blockedReason the note the blocking administrator left; never shown to the person
 */
public record AdminUserResponse(Long id, String fullName, String email, UserRole role, Instant createdAt,
                                EligibilityStatus eligibilityStatus, int noShows,
                                Instant blockedAt, String blockedReason) {

    public static AdminUserResponse from(User u, int noShows) {
        return new AdminUserResponse(u.getId(), u.getFullName(), u.getEmail(), u.getRole(), u.getCreatedAt(),
                u.getEligibilityStatus(), noShows, u.getBlockedAt(), u.getBlockedReason());
    }
}

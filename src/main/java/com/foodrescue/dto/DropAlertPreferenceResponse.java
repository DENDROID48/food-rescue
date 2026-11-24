package com.foodrescue.dto;

import com.foodrescue.domain.DropAlertPreference;

public record DropAlertPreferenceResponse(boolean enabled, Double latitude, Double longitude, double radiusKm,
                                          int maxPerDay) {

    public static final DropAlertPreferenceResponse NONE = new DropAlertPreferenceResponse(false, null, null, 2, 3);

    public static DropAlertPreferenceResponse from(DropAlertPreference p) {
        return new DropAlertPreferenceResponse(p.isEnabled(), p.getLatitude(), p.getLongitude(), p.getRadiusKm(),
                p.getMaxPerDay());
    }
}

package com.foodrescue.dto;

import com.foodrescue.domain.FoodTag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalTime;
import java.util.Set;

/**
 * @param quietStart  no push messages from this time … (null with quietEnd for none)
 * @param quietEnd    … until this time; may be earlier than quietStart (overnight)
 * @param digestTime  daily summary at this local time; null for none
 * @param timeZone    the customer's own IANA zone, for example "Europe/Bucharest"
 */
public record RadarProfileRequest(
        boolean personalised,
        @Size(max = 20) Set<FoodTag> interests,
        boolean notifyFavourites,
        LocalTime quietStart,
        LocalTime quietEnd,
        LocalTime digestTime,
        @NotBlank @Size(max = 40) String timeZone,
        /** Reminder before a pickup closes; null keeps the current setting (on by default). */
        Boolean pickupReminders) {
}

package com.foodrescue.dto;

import com.foodrescue.domain.FoodTag;

import java.time.LocalTime;
import java.util.Set;

/**
 * @param throttled true while fewer alerts are sent because recent ones were not opened
 * @param pushDevices browsers of this customer that receive push messages
 * @param freeItemsOffer whether Browse shows the offer to get verified for free items
 */
public record RadarProfileResponse(boolean personalised, Set<FoodTag> interests, boolean notifyFavourites,
                                   LocalTime quietStart, LocalTime quietEnd, LocalTime digestTime, String timeZone,
                                   boolean throttled, long pushDevices, boolean pickupReminders,
                                   boolean freeItemsOffer) {
}

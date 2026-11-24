package com.foodrescue.domain;

import com.foodrescue.util.GeoUtils;

/**
 * Something with a position on the map: a {@link Store}, a customer's Rescue Drop alert area
 * ({@link DropAlertPreference}), or the place a customer searches from ({@link GeoPoint}).
 * Coordinates are in degrees (WGS84, the system GPS uses).
 */
public interface Locatable {

    /** Degrees north (+) or south (-) of the equator, -90 to 90. */
    double getLatitude();

    /** Degrees east (+) or west (-) of the prime meridian, -180 to 180. */
    double getLongitude();

    /** Straight line distance to another place, in kilometres. */
    default double distanceKmTo(Locatable other) {
        return GeoUtils.haversineKm(getLatitude(), getLongitude(), other.getLatitude(), other.getLongitude());
    }
}

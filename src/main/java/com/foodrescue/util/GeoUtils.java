package com.foodrescue.util;

/**
 * Small, dependency free geographic helpers.
 *
 * <p>Both methods assume a spherical Earth, which is accurate to roughly 0.5 percent. That is
 * more than enough for "which shop is within 3 km of me".</p>
 */
public final class GeoUtils {

    /** Mean Earth radius in kilometres. */
    public static final double EARTH_RADIUS_KM = 6371.0088;

    /** Approximate length of one degree of latitude, constant everywhere on Earth. */
    private static final double KM_PER_DEGREE_LATITUDE = 111.32;

    private GeoUtils() {
        // Utility class, no instances.
    }

    /**
     * Great circle distance between two points using the Haversine formula.
     *
     * @return distance in kilometres
     */
    public static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);

        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /**
     * Rectangle that fully contains the circle of the given radius around a centre point.
     * Used as a fast database pre-filter (see {@code FoodItemRepository}).
     *
     * <p>Known limitations, acceptable for a city scale search: the box does not wrap across
     * the 180th meridian and is not meaningful within the radius of a pole.</p>
     */
    public static BoundingBox boundingBox(double lat, double lng, double radiusKm) {
        double latDelta = radiusKm / KM_PER_DEGREE_LATITUDE;

        // Lines of longitude get closer together towards the poles, so one degree of longitude
        // covers fewer kilometres the further you are from the equator.
        double kmPerDegreeLongitude = KM_PER_DEGREE_LATITUDE * Math.cos(Math.toRadians(lat));
        double lngDelta = radiusKm / Math.max(kmPerDegreeLongitude, 0.0001);

        return new BoundingBox(lat - latDelta, lat + latDelta, lng - lngDelta, lng + lngDelta);
    }

    /** Immutable rectangle in degrees. */
    public record BoundingBox(double minLat, double maxLat, double minLng, double maxLng) {
    }
}

package com.foodrescue.domain;

/** A bare position, for example where a consumer is searching from. */
public record GeoPoint(double latitude, double longitude) implements Locatable {

    @Override
    public double getLatitude() {
        return latitude;
    }

    @Override
    public double getLongitude() {
        return longitude;
    }
}

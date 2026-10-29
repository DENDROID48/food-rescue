package com.foodrescue.dto;

/** A place found by address lookup: a readable name and where it is. */
public record PlaceResponse(String label, double latitude, double longitude) {
}

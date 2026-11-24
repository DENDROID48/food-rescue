package com.foodrescue.dto;

import java.time.LocalTime;

/** Daily in-app reminder. time is required when enabled (a time of day in the store's zone). */
public record ReminderRequest(boolean enabled, LocalTime time) {
}

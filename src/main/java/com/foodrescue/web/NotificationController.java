package com.foodrescue.web;

import com.foodrescue.dto.DropAlertPreferenceRequest;
import com.foodrescue.dto.DropAlertPreferenceResponse;
import com.foodrescue.dto.NotificationResponse;
import com.foodrescue.security.CurrentUser;
import com.foodrescue.service.AlertService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** A customer's Rescue Drop alert settings and in-app inbox. Consumers only (SecurityConfig). */
@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
public class NotificationController {

    private final AlertService alertService;

    /** GET /api/v1/me/notifications   the 30 newest alerts and the unread count. */
    @GetMapping("/notifications")
    public NotificationResponse.Inbox inbox(@CurrentUser Long userId) {
        return alertService.inbox(userId);
    }

    /** POST /api/v1/me/notifications/read   marks every alert as read. */
    @PostMapping("/notifications/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@CurrentUser Long userId) {
        alertService.markAllRead(userId);
    }

    @GetMapping("/drop-alerts")
    public DropAlertPreferenceResponse preference(@CurrentUser Long userId) {
        return alertService.preference(userId);
    }

    /** PUT /api/v1/me/drop-alerts   body: {"enabled": true, "latitude": .., "longitude": .., "radiusKm": 2, "maxPerDay": 3} */
    @PutMapping("/drop-alerts")
    public DropAlertPreferenceResponse savePreference(@CurrentUser Long userId,
                                                      @Valid @RequestBody DropAlertPreferenceRequest request) {
        return alertService.savePreference(userId, request);
    }
}

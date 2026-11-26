package com.foodrescue.web;

import com.foodrescue.config.FoodRescueProperties;
import com.foodrescue.service.AccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The rules the web page needs to show, read from configuration, so the page and the server
 * never disagree (for example after starting with --foodrescue.max-quantity-per-reservation=2).
 */
@RestController
@RequiredArgsConstructor
public class SettingsController {

    private final FoodRescueProperties properties;
    private final AccountService accountService;

    /** GET /api/v1/settings   public. */
    @GetMapping("/api/v1/settings")
    public Map<String, Object> settings() {
        return Map.of("maxQuantityPerReservation", properties.maxQuantityPerReservation(),
                // "Forgot password" is only offered when reset emails can actually be sent.
                "passwordReset", accountService.resetAvailable());
    }
}

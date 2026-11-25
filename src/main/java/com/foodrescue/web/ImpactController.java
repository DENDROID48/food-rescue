package com.foodrescue.web;

import com.foodrescue.dto.ImpactResponse;
import com.foodrescue.security.CurrentUser;
import com.foodrescue.service.GamificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** A customer's personal impact dashboard: savings, streak, achievements, community challenge. */
@RestController
@RequiredArgsConstructor
public class ImpactController {

    private final GamificationService gamificationService;

    /** GET /api/v1/me/impact   customers only (covered by the /api/v1/me/** rule). */
    @GetMapping("/api/v1/me/impact")
    public ImpactResponse impact(@CurrentUser Long userId) {
        return gamificationService.impact(userId);
    }
}

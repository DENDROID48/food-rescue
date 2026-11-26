package com.foodrescue.web;

import com.foodrescue.dto.AdminStoreResponse;
import com.foodrescue.dto.EligibilityDecisionRequest;
import com.foodrescue.dto.StoreStatusRequest;
import com.foodrescue.dto.UserResponse;
import com.foodrescue.security.CurrentUser;
import com.foodrescue.service.AdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Administrator endpoints. SecurityConfig lets only ADMIN accounts through. */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;
    private final com.foodrescue.service.AnalyticsService analyticsService;

    /** GET /api/v1/admin/insights   the platform over the last 28 days, quiet stores and stores needing attention. */
    @GetMapping("/insights")
    public com.foodrescue.dto.AdminInsights insights() {
        return analyticsService.adminOverview();
    }

    /** GET /api/v1/admin/eligibility-requests   consumers waiting for verification, oldest first. */
    @GetMapping("/eligibility-requests")
    public List<UserResponse> pendingEligibility() {
        return adminService.listPendingEligibility();
    }

    /** PUT /api/v1/admin/users/{userId}/eligibility   body: {"approved": true} */
    @PutMapping("/users/{userId}/eligibility")
    public UserResponse decideEligibility(@CurrentUser Long adminId,
                                          @PathVariable Long userId,
                                          @Valid @RequestBody EligibilityDecisionRequest request) {
        return adminService.decideEligibility(adminId, userId, request.approved());
    }

    /** GET /api/v1/admin/users?q=ana   people whose name or email contains the text (at most 50). */
    @GetMapping("/users")
    public List<com.foodrescue.dto.AdminUserResponse> users(@RequestParam(defaultValue = "") String q) {
        return adminService.listUsers(q);
    }

    /** POST /api/v1/admin/users/{userId}/block   body: {"reason": "..."} (optional, for administrators only). */
    @PostMapping("/users/{userId}/block")
    public com.foodrescue.dto.AdminUserResponse block(@CurrentUser Long adminId, @PathVariable Long userId,
                                                       @Valid @RequestBody com.foodrescue.dto.BlockRequest request) {
        return adminService.block(adminId, userId, request.reason());
    }

    /** POST /api/v1/admin/users/{userId}/unblock */
    @PostMapping("/users/{userId}/unblock")
    public com.foodrescue.dto.AdminUserResponse unblock(@CurrentUser Long adminId, @PathVariable Long userId) {
        return adminService.unblock(adminId, userId);
    }

    /** GET /api/v1/admin/stores   every store with its owner. */
    @GetMapping("/stores")
    public List<AdminStoreResponse> stores() {
        return adminService.listStores();
    }

    /** PUT /api/v1/admin/stores/{storeId}/status   body: {"active": false} suspends the store. */
    @PutMapping("/stores/{storeId}/status")
    public AdminStoreResponse setStoreStatus(@CurrentUser Long adminId,
                                             @PathVariable Long storeId,
                                             @Valid @RequestBody StoreStatusRequest request) {
        return adminService.setStoreActive(adminId, storeId, request.active());
    }
}

package com.foodrescue.web;

import com.foodrescue.dto.FavouritesResponse;
import com.foodrescue.dto.PushSubscriptionRequest;
import com.foodrescue.dto.RadarProfileRequest;
import com.foodrescue.dto.RadarProfileResponse;
import com.foodrescue.dto.RadarResponse;
import com.foodrescue.push.PushSubscriptionService;
import com.foodrescue.push.WebPushSender;
import com.foodrescue.security.CurrentUser;
import com.foodrescue.service.RadarService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Rescue Radar for customers (SecurityConfig: consumers only, except the public push key):
 * recommendations, favourites, preferences and push subscriptions.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Validated
public class RadarController {

    private final RadarService radarService;
    private final PushSubscriptionService pushSubscriptionService;
    private final WebPushSender webPushSender;

    /** GET /api/v1/me/radar?lat=..&lng=..&radiusKm=3   "For you" picks with reasons, and favourite ids. */
    @GetMapping("/me/radar")
    public RadarResponse radar(@CurrentUser Long userId,
                               @RequestParam @DecimalMin("-90") @DecimalMax("90") double lat,
                               @RequestParam @DecimalMin("-180") @DecimalMax("180") double lng,
                               @RequestParam(defaultValue = "3") double radiusKm) {
        return radarService.recommend(userId, lat, lng, radiusKm);
    }

    @GetMapping("/me/favourites")
    public FavouritesResponse favourites(@CurrentUser Long userId) {
        return radarService.favourites(userId);
    }

    @PutMapping("/me/favourites/stores/{storeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addStore(@CurrentUser Long userId, @PathVariable Long storeId) {
        radarService.addStore(userId, storeId);
    }

    @DeleteMapping("/me/favourites/stores/{storeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeStore(@CurrentUser Long userId, @PathVariable Long storeId) {
        radarService.removeStore(userId, storeId);
    }

    /** A favourite product is the shop's saved product (templateId), so it carries over every day. */
    @PutMapping("/me/favourites/products/{templateId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addProduct(@CurrentUser Long userId, @PathVariable Long templateId) {
        radarService.addProduct(userId, templateId);
    }

    @DeleteMapping("/me/favourites/products/{templateId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeProduct(@CurrentUser Long userId, @PathVariable Long templateId) {
        radarService.removeProduct(userId, templateId);
    }

    @GetMapping("/me/radar-profile")
    public RadarProfileResponse profile(@CurrentUser Long userId) {
        return radarService.profile(userId);
    }

    @PutMapping("/me/radar-profile")
    public RadarProfileResponse saveProfile(@CurrentUser Long userId, @Valid @RequestBody RadarProfileRequest request) {
        return radarService.saveProfile(userId, request);
    }

    // ---------- Web Push ----------

    /** GET /api/v1/push/public-key   the VAPID key browsers need to subscribe. Public. */
    @GetMapping("/push/public-key")
    public Map<String, String> publicKey() {
        return Map.of("publicKey", webPushSender.publicKey());
    }

    /** POST /api/v1/me/push-subscriptions   body: the browser's subscription.toJSON() */
    @PostMapping("/me/push-subscriptions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void subscribe(@CurrentUser Long userId, @Valid @RequestBody PushSubscriptionRequest request) {
        pushSubscriptionService.subscribe(userId, request);
    }

    /** DELETE /api/v1/me/push-subscriptions   body: {"endpoint": "..."} */
    @DeleteMapping("/me/push-subscriptions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unsubscribe(@CurrentUser Long userId, @RequestBody Map<String, @NotBlank String> body) {
        pushSubscriptionService.unsubscribe(userId, body.getOrDefault("endpoint", ""));
    }

    /** POST /api/v1/me/push-subscriptions/test   sends a test message to this customer's browsers. */
    @PostMapping("/me/push-subscriptions/test")
    public Map<String, Integer> test(@CurrentUser Long userId) {
        return Map.of("sent", pushSubscriptionService.test(userId));
    }
}

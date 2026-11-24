package com.foodrescue.web;

import com.foodrescue.storage.PhotoFile;
import com.foodrescue.dto.NearbyFoodItemResponse;
import com.foodrescue.dto.OrderResponse;
import com.foodrescue.dto.ReserveRequest;
import com.foodrescue.security.CurrentUser;
import com.foodrescue.service.FoodItemPhotoService;
import com.foodrescue.service.FoodItemService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;

/**
 * HTTP layer. It only translates between HTTP and Java and delegates every decision to
 * {@link FoodItemService}.
 *
 * <p>Searching is open to everyone; reserving and claiming need a signed-in consumer
 * (see SecurityConfig). {@link CurrentUser} supplies the id from the verified login token.</p>
 */
@RestController
@RequestMapping("/api/v1/food-items")
@RequiredArgsConstructor
@Validated // Enables validation of @RequestParam and @RequestHeader constraints.
public class FoodItemController {

    private final FoodItemService foodItemService;
    private final FoodItemPhotoService photoService;

    /**
     * GET /api/v1/food-items/nearby?lat=45.75&lng=21.23&radiusKm=3
     *
     * <p>Lists available discounted food around the user, nearest first.</p>
     */
    @GetMapping("/nearby")
    public List<NearbyFoodItemResponse> listNearby(
            @RequestParam @DecimalMin("-90") @DecimalMax("90") double lat,
            @RequestParam @DecimalMin("-180") @DecimalMax("180") double lng,
            @RequestParam(defaultValue = "3") double radiusKm) {
        return foodItemService.findAvailableNearby(lat, lng, radiusKm);
    }

    /**
     * GET /api/v1/food-items/{id}/photo
     *
     * <p>The photo link includes the item version, so the photo behind one link never changes
     * and browsers may cache it for a day.</p>
     */
    @GetMapping("/{id}/photo")
    public ResponseEntity<byte[]> photo(@PathVariable Long id) {
        PhotoFile photo = photoService.get(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(photo.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
                .body(photo.data());
    }

    /**
     * POST /api/v1/food-items/{id}/reservations   body: {"quantity": 2}
     *
     * <p>Reserves units at the discounted price. Responds 201 Created with the pickup code.</p>
     */
    @PostMapping("/{id}/reservations")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse reserve(@PathVariable Long id,
                                 @CurrentUser Long userId,
                                 @Valid @RequestBody(required = false) ReserveRequest request) {
        // The body is optional: a missing body means "reserve one unit".
        ReserveRequest body = request != null ? request : new ReserveRequest(null);
        return foodItemService.reserve(userId, id, body.quantity());
    }

    /**
     * POST /api/v1/food-items/{id}/free-claims
     *
     * <p>Claims one free Pay-It-Forward unit. Responds 201 Created with the pickup code.</p>
     */
    @PostMapping("/{id}/free-claims")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse claimFree(@PathVariable Long id,
                                   @CurrentUser Long userId) {
        return foodItemService.claimPayItForward(userId, id);
    }
}

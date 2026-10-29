package com.foodrescue.web;

import com.foodrescue.domain.OrderStatus;
import com.foodrescue.dto.FoodItemRequest;
import com.foodrescue.dto.FoodItemResponse;
import com.foodrescue.dto.OrderResponse;
import com.foodrescue.dto.PickupRequest;
import com.foodrescue.dto.StoreRequest;
import com.foodrescue.dto.StoreResponse;
import com.foodrescue.security.CurrentUser;
import com.foodrescue.service.FoodItemPhotoService;
import com.foodrescue.service.OrderService;
import com.foodrescue.service.StoreService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * Store owner endpoints: register stores, publish food, see reservations and confirm pickups.
 * Every call checks that the signed-in user owns the store in the path.
 */
@RestController
@RequestMapping("/api/v1/stores")
@RequiredArgsConstructor
public class StoreController {

    private final StoreService storeService;
    private final OrderService orderService;
    private final FoodItemPhotoService photoService;

    /** POST /api/v1/stores   body: {"name": "...", "address": "...", "latitude": 45.75, "longitude": 21.22} */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public StoreResponse createStore(@CurrentUser Long userId,
                                     @Valid @RequestBody StoreRequest request) {
        return storeService.createStore(userId, request);
    }

    /** PUT /api/v1/stores/{storeId}   changes name and address (the position follows the address). */
    @PutMapping("/{storeId}")
    public StoreResponse updateStore(@CurrentUser Long userId,
                                     @PathVariable Long storeId,
                                     @Valid @RequestBody StoreRequest request) {
        return storeService.updateStore(userId, storeId, request);
    }

    /** GET /api/v1/stores/mine   stores managed by the caller. */
    @GetMapping("/mine")
    public List<StoreResponse> myStores(@CurrentUser Long userId) {
        return storeService.listOwnStores(userId);
    }

    /** GET /api/v1/stores/{storeId}/food-items   every item of the store, including sold out and expired. */
    @GetMapping("/{storeId}/food-items")
    public List<FoodItemResponse> listItems(@CurrentUser Long userId,
                                            @PathVariable Long storeId) {
        return storeService.listItems(userId, storeId);
    }

    /** POST /api/v1/stores/{storeId}/food-items   publishes surplus food. */
    @PostMapping("/{storeId}/food-items")
    @ResponseStatus(HttpStatus.CREATED)
    public FoodItemResponse createItem(@CurrentUser Long userId,
                                       @PathVariable Long storeId,
                                       @Valid @RequestBody FoodItemRequest request) {
        return storeService.createItem(userId, storeId, request);
    }

    /** PUT /api/v1/stores/{storeId}/food-items/{itemId}   replaces every field of an item. */
    @PutMapping("/{storeId}/food-items/{itemId}")
    public FoodItemResponse updateItem(@CurrentUser Long userId,
                                       @PathVariable Long storeId,
                                       @PathVariable Long itemId,
                                       @Valid @RequestBody FoodItemRequest request) {
        return storeService.updateItem(userId, storeId, itemId, request);
    }

    /** DELETE /api/v1/stores/{storeId}/food-items/{itemId}   takes an item off the market. */
    @DeleteMapping("/{storeId}/food-items/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteItem(@CurrentUser Long userId,
                           @PathVariable Long storeId,
                           @PathVariable Long itemId) {
        storeService.deleteItem(userId, storeId, itemId);
    }

    /** POST /api/v1/stores/{storeId}/food-items/{itemId}/photo   multipart form field "file": JPEG, PNG or WebP, at most 2 MB. */
    @PostMapping(path = "/{storeId}/food-items/{itemId}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void uploadPhoto(@CurrentUser Long userId,
                            @PathVariable Long storeId,
                            @PathVariable Long itemId,
                            @RequestParam("file") MultipartFile file) throws IOException {
        photoService.upload(userId, storeId, itemId, file.getBytes());
    }

    /** DELETE /api/v1/stores/{storeId}/food-items/{itemId}/photo */
    @DeleteMapping("/{storeId}/food-items/{itemId}/photo")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePhoto(@CurrentUser Long userId,
                            @PathVariable Long storeId,
                            @PathVariable Long itemId) {
        photoService.delete(userId, storeId, itemId);
    }

    /** GET /api/v1/stores/{storeId}/orders?status=RESERVED   reservations to prepare, soonest deadline first. */
    @GetMapping("/{storeId}/orders")
    public List<OrderResponse> listOrders(@CurrentUser Long userId,
                                          @PathVariable Long storeId,
                                          @RequestParam(defaultValue = "RESERVED") OrderStatus status) {
        return orderService.listStoreOrders(userId, storeId, status);
    }

    /** POST /api/v1/stores/{storeId}/pickups   body: {"pickupCode": "048213"}   marks the order as collected. */
    @PostMapping("/{storeId}/pickups")
    public OrderResponse confirmPickup(@CurrentUser Long userId,
                                       @PathVariable Long storeId,
                                       @Valid @RequestBody PickupRequest request) {
        return orderService.confirmPickup(userId, storeId, request.pickupCode());
    }
}

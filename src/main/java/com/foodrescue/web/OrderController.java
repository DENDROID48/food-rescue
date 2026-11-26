package com.foodrescue.web;

import com.foodrescue.dto.OrderResponse;
import com.foodrescue.security.CurrentUser;
import com.foodrescue.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Consumer endpoints for their own orders. */
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final com.foodrescue.service.NoShowService noShowService;

    /** GET /api/v1/orders/standing   missed pickups and any limit they bring (see NoShowService). */
    @GetMapping("/standing")
    public com.foodrescue.service.NoShowService.Standing standing(@CurrentUser Long userId) {
        return noShowService.standing(userId);
    }

    /** GET /api/v1/orders/mine   order history of the caller, newest first. */
    @GetMapping("/mine")
    public List<OrderResponse> myOrders(@CurrentUser Long userId) {
        return orderService.listOwnOrders(userId);
    }

    /** POST /api/v1/orders/{orderId}/cancel   cancels an open reservation and returns the stock. */
    @PostMapping("/{orderId}/cancel")
    public OrderResponse cancel(@CurrentUser Long userId,
                                @PathVariable Long orderId) {
        return orderService.cancel(userId, orderId);
    }
}

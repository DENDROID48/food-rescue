package com.foodrescue.service;

import com.foodrescue.domain.FoodItem;
import com.foodrescue.domain.Order;
import com.foodrescue.domain.OrderStatus;
import com.foodrescue.dto.OrderResponse;
import com.foodrescue.exception.BusinessRuleException;
import com.foodrescue.exception.ResourceNotFoundException;
import com.foodrescue.repository.FoodItemRepository;
import com.foodrescue.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * What happens to an order after it was created: the consumer looks it up or cancels it, and
 * the store hands the food over when the customer shows the pickup code.
 *
 * <pre>
 *   RESERVED --(consumer cancels)------> CANCELLED   stock returned
 *   RESERVED --(store enters the code)--> PICKED_UP
 *   RESERVED --(deadline passes)-------> EXPIRED     see ReservationExpiryJob
 * </pre>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final FoodItemRepository foodItemRepository;
    private final StoreService storeService;
    private final Clock clock;

    // ------------------------------------------------------------------------------------
    // Consumer side
    // ------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<OrderResponse> listOwnOrders(Long userId) {
        return orderRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(OrderResponse::from)
                .toList();
    }

    /**
     * Cancels an open reservation and gives the units back, so someone else can have them.
     * Units are only returned while the food has not expired, as in the expiry job.
     */
    @Transactional
    public OrderResponse cancel(Long userId, Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));
        // Someone else's order is reported as missing, so order ids of other users are not revealed.
        if (!order.getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Order", orderId);
        }
        requireReserved(order);

        Instant now = clock.instant();
        FoodItem item = foodItemRepository.findByIdForUpdate(order.getFoodItem().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Food item", order.getFoodItem().getId()));
        if (item.isNotExpired(now)) {
            item.returnStock(order.getType(), order.getQuantity());
        }
        order.setStatus(OrderStatus.CANCELLED);

        log.info("User {} cancelled order {}", userId, orderId);
        return OrderResponse.from(order);
    }

    // ------------------------------------------------------------------------------------
    // Store side
    // ------------------------------------------------------------------------------------

    /** Orders of one store in the given status, for example the reservations still to be collected. */
    @Transactional(readOnly = true)
    public List<OrderResponse> listStoreOrders(Long ownerId, Long storeId, OrderStatus status) {
        storeService.requireOwnedStore(ownerId, storeId);
        return orderRepository.findByFoodItemStoreIdAndStatusOrderByPickupDeadlineAsc(storeId, status).stream()
                .map(OrderResponse::from)
                .toList();
    }

    /**
     * Called by the store when a customer collects food. The customer shows the six digit
     * code; the store enters it and hands over the food once this call succeeds.
     */
    @Transactional
    public OrderResponse confirmPickup(Long ownerId, Long storeId, String pickupCode) {
        storeService.requireOwnedStore(ownerId, storeId);

        Order order = orderRepository
                .findByFoodItemStoreIdAndStatusAndPickupCode(storeId, OrderStatus.RESERVED, pickupCode)
                .orElseThrow(() -> new BusinessRuleException(HttpStatus.NOT_FOUND,
                        "No open reservation with this pickup code at this store"));

        // The expiry job runs once a minute, so a reservation can be past its deadline but not
        // released yet. It must not be handed over: the stock may be returned at any moment.
        if (order.isExpiredAt(clock.instant())) {
            throw new BusinessRuleException(HttpStatus.GONE, "The pickup deadline for this reservation has passed");
        }

        order.setStatus(OrderStatus.PICKED_UP);
        log.info("Store {} handed over order {}", storeId, order.getId());
        return OrderResponse.from(order);
    }

    private void requireReserved(Order order) {
        if (order.getStatus() != OrderStatus.RESERVED) {
            throw new BusinessRuleException(HttpStatus.CONFLICT,
                    "Only open reservations can be changed; this order is " + order.getStatus());
        }
    }
}

package com.foodrescue.service;

import com.foodrescue.domain.FoodItem;
import com.foodrescue.domain.Order;
import com.foodrescue.domain.OrderStatus;
import com.foodrescue.repository.FoodItemRepository;
import com.foodrescue.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Background task that releases reservations nobody collected, so that the food can be
 * offered to someone else instead of going to waste.
 *
 * <p>Runs every minute. A released unit goes back into the stock counter it came from
 * (paid or Pay-It-Forward), but only while the food itself has not expired.</p>
 *
 * <p>If the application runs on several servers, add a distributed lock (for example
 * ShedLock) so that only one instance runs the job at a time.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReservationExpiryJob {

    private final OrderRepository orderRepository;
    private final FoodItemRepository foodItemRepository;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${foodrescue.expiry-job-delay-ms:60000}")
    @Transactional
    public void releaseExpiredReservations() {
        Instant now = clock.instant();
        List<Order> overdue = orderRepository.findByStatusAndPickupDeadlineBefore(OrderStatus.RESERVED, now);

        for (Order order : overdue) {
            order.setStatus(OrderStatus.EXPIRED);

            // Lock the item row so this restock cannot race with a customer reserving it.
            FoodItem item = foodItemRepository.findByIdForUpdate(order.getFoodItem().getId()).orElse(null);
            if (item != null && item.isNotExpired(now)) {
                item.returnStock(order.getType(), order.getQuantity());
            }
        }

        if (!overdue.isEmpty()) {
            log.info("Released {} uncollected reservation(s)", overdue.size());
        }
    }
}

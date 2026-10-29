package com.foodrescue.repository;

import com.foodrescue.domain.Order;
import com.foodrescue.domain.OrderStatus;
import com.foodrescue.domain.OrderType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /**
     * Order history of one user, newest first. The entity graph loads the food item and its
     * store in the same query, because every response shows the item and store names.
     */
    @EntityGraph(attributePaths = {"foodItem", "foodItem.store"})
    List<Order> findByUserIdOrderByCreatedAtDesc(Long userId);

    /** Orders of one store in a given status, the ones that must be collected soonest first. */
    @EntityGraph(attributePaths = {"foodItem", "foodItem.store"})
    List<Order> findByFoodItemStoreIdAndStatusOrderByPickupDeadlineAsc(Long storeId, OrderStatus status);

    /** Looks up the reservation a customer is collecting by the code they show at the counter. */
    @EntityGraph(attributePaths = {"foodItem", "foodItem.store"})
    Optional<Order> findByFoodItemStoreIdAndStatusAndPickupCode(Long storeId, OrderStatus status, String pickupCode);

    /** Used to keep pickup codes unique among the open reservations of one store. */
    boolean existsByFoodItemStoreIdAndStatusAndPickupCode(Long storeId, OrderStatus status, String pickupCode);

    /** True when an item was ever ordered, in which case it cannot be deleted from the database. */
    boolean existsByFoodItemId(Long foodItemId);

    /**
     * Counts how many orders of a given type a user created since a point in time, ignoring
     * orders in the given status. Used to enforce the daily limit on free Pay-It-Forward claims
     * so that the pool is shared fairly; cancelled claims do not count against the limit.
     */
    long countByUserIdAndTypeAndStatusNotAndCreatedAtAfter(Long userId, OrderType type,
                                                           OrderStatus excludedStatus, Instant since);

    /** Reservations that were never collected before their deadline (input for the cleanup job). */
    List<Order> findByStatusAndPickupDeadlineBefore(OrderStatus status, Instant deadline);
}

package com.foodrescue.repository;

import com.foodrescue.domain.Order;
import com.foodrescue.domain.OrderStatus;
import com.foodrescue.domain.OrderType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /** Units in orders of one store with the given status, for example everything still to be collected. */
    @Query("select coalesce(sum(o.quantity), 0) from Order o where o.foodItem.store.id = :storeId and o.status = :status")
    long sumUnitsByStatus(@Param("storeId") Long storeId, @Param("status") OrderStatus status);

    /** Units handed over by a store between two moments (for example the store's local day). */
    @Query("""
            select coalesce(sum(o.quantity), 0) from Order o
            where o.foodItem.store.id = :storeId and o.status = com.foodrescue.domain.OrderStatus.PICKED_UP
              and o.pickedUpAt >= :from and o.pickedUpAt < :to
            """)
    long sumCollectedUnits(@Param("storeId") Long storeId, @Param("from") Instant from, @Param("to") Instant to);

    /** Like {@link #sumCollectedUnits} for one order type, for example donated Pay-It-Forward units. */
    @Query("""
            select coalesce(sum(o.quantity), 0) from Order o
            where o.foodItem.store.id = :storeId and o.status = com.foodrescue.domain.OrderStatus.PICKED_UP
              and o.type = :type and o.pickedUpAt >= :from and o.pickedUpAt < :to
            """)
    long sumCollectedUnitsOfType(@Param("storeId") Long storeId, @Param("type") OrderType type,
                                 @Param("from") Instant from, @Param("to") Instant to);

    /**
     * Units per offer, order type and status, for a set of offers: one row is
     * [foodItemId, OrderType, OrderStatus, units]. Feeds the Autopilot and the stock alerts.
     */
    @Query("""
            select o.foodItem.id, o.type, o.status, sum(o.quantity) from Order o
            where o.foodItem.id in :itemIds
            group by o.foodItem.id, o.type, o.status
            """)
    List<Object[]> unitsByItem(@Param("itemIds") java.util.Collection<Long> itemIds);

    /**
     * What a customer collected since a moment, per shop and saved product: rows of
     * [storeId, templateId or null, number of orders]. Feeds the Rescue Radar.
     */
    @Query("""
            select s.id, t.id, count(o) from Order o join o.foodItem f join f.store s left join f.template t
            where o.user.id = :userId and o.status = com.foodrescue.domain.OrderStatus.PICKED_UP and o.createdAt >= :since
            group by s.id, t.id
            """)
    List<Object[]> collectedBy(@Param("userId") Long userId, @Param("since") Instant since);

    /** Open reservations whose pickup closes in a time range and that got no reminder yet. */
    @EntityGraph(attributePaths = {"foodItem", "foodItem.store"})
    @Query("""
            select o from Order o
            where o.status = com.foodrescue.domain.OrderStatus.RESERVED and o.reminderSentAt is null
              and o.pickupDeadline > :from and o.pickupDeadline <= :to
            """)
    List<Order> dueForReminder(@Param("from") Instant from, @Param("to") Instant to);

    /** Reservations that were never collected before their deadline (input for the cleanup job). */
    List<Order> findByStatusAndPickupDeadlineBefore(OrderStatus status, Instant deadline);

    /** A customer's collected orders, oldest first, with the offer and shop (input for the Impact page). */
    @EntityGraph(attributePaths = {"foodItem", "foodItem.store"})
    List<Order> findByUserIdAndStatusOrderByPickedUpAtAsc(Long userId, OrderStatus status);

    /** Units every customer together collected in a time range (the community challenge). */
    @Query("""
            select coalesce(sum(o.quantity), 0) from Order o
            where o.status = com.foodrescue.domain.OrderStatus.PICKED_UP and o.pickedUpAt >= :from and o.pickedUpAt < :to
            """)
    long sumCollectedBetween(@Param("from") Instant from, @Param("to") Instant to);
}

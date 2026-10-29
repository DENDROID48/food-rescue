package com.foodrescue.repository;

import com.foodrescue.domain.FoodItem;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface FoodItemRepository extends JpaRepository<FoodItem, Long> {

    /**
     * Step one of the geographic search: a cheap rectangular pre-filter done by the database.
     *
     * <p>Computing a true circular distance for every row in SQL would force a full table scan.
     * Instead, the service converts the radius into a latitude/longitude rectangle (a bounding
     * box). This query uses the index on store location to return only candidates inside that
     * box. The service then removes the corners of the box with the exact Haversine formula.</p>
     *
     * <p>"join fetch f.store" loads the store in the same SQL statement, avoiding one extra
     * query per item (the classic N+1 problem).</p>
     *
     * <p>An item is "available" when its store is active, it has not expired, and it still has
     * either paid stock or free Pay-It-Forward stock. Both counters are returned so the app can
     * show the discounted offer and the free option side by side.</p>
     */
    @Query("""
            select f from FoodItem f
            join fetch f.store s
            where s.active = true
              and f.expiresAt > :now
              and (f.quantityAvailable > 0 or f.payItForwardQuantity > 0)
              and s.latitude between :minLat and :maxLat
              and s.longitude between :minLng and :maxLng
            order by f.expiresAt asc
            """)
    List<FoodItem> findAvailableInBoundingBox(@Param("now") Instant now,
                                              @Param("minLat") double minLat,
                                              @Param("maxLat") double maxLat,
                                              @Param("minLng") double minLng,
                                              @Param("maxLng") double maxLng);

    /** Every item of one store, including sold out and expired ones (store owner view). */
    List<FoodItem> findByStoreIdOrderByExpiresAtDesc(Long storeId);

    /**
     * Loads one item and locks its database row until the current transaction ends
     * (SELECT ... FOR UPDATE). A second customer trying to reserve the same item waits here,
     * then sees the already reduced stock. This prevents selling more units than exist.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from FoodItem f join fetch f.store where f.id = :id")
    Optional<FoodItem> findByIdForUpdate(@Param("id") Long id);
}

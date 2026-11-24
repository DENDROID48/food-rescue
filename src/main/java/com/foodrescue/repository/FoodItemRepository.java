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
     * <p>An item is "available" when its store is active, the store published it (status ACTIVE), it has not expired, and it still has
     * either paid stock or free Pay-It-Forward stock. Both counters are returned so the app can
     * show the discounted offer and the free option side by side.</p>
     */
    @Query("""
            select f from FoodItem f
            join fetch f.store s
            where s.active = true
              and f.status = com.foodrescue.domain.OfferStatus.ACTIVE
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

    /**
     * Offers of a store that still run after the given moment, soonest end first. Called with
     * the start of the store's local day, it returns today's offers and never yesterday's.
     */
    List<FoodItem> findByStoreIdAndExpiresAtAfterOrderByExpiresAtAsc(Long storeId, Instant after);

    /** True when the template already has a regular offer (not a drop) that has not ended, in any status. */
    boolean existsByTemplateIdAndRescueDropFalseAndExpiresAtAfter(Long templateId, Instant now);

    /** True when a Rescue Drop of this template is still running. */
    boolean existsByTemplateIdAndRescueDropTrueAndExpiresAtAfter(Long templateId, Instant now);

    /**
     * Regular offers made from templates that ended between two moments: the history the
     * Autopilot learns from. Drops are left out, because they are extra, unplanned stock.
     */
    @Query("""
            select f from FoodItem f join fetch f.template
            where f.store.id = :storeId and f.rescueDrop = false
              and f.status <> com.foodrescue.domain.OfferStatus.DRAFT
              and f.expiresAt > :from and f.expiresAt <= :to
            order by f.expiresAt desc
            """)
    List<FoodItem> findEndedTemplateOffers(@Param("storeId") Long storeId, @Param("from") Instant from,
                                           @Param("to") Instant to);

    /** Offers a store created since a moment (any status); used for the daily reminder. */
    long countByStoreIdAndCreatedAtGreaterThanEqual(Long storeId, Instant since);

    /** Stock customers can reserve right now. */
    @Query("""
            select coalesce(sum(f.quantityAvailable), 0) as forSale,
                   coalesce(sum(f.payItForwardQuantity), 0) as free,
                   count(f) as offers
            from FoodItem f
            where f.store.id = :storeId
              and f.status = com.foodrescue.domain.OfferStatus.ACTIVE
              and f.expiresAt > :now
              and (f.quantityAvailable > 0 or f.payItForwardQuantity > 0)
            """)
    StockTotals sumLiveStock(@Param("storeId") Long storeId, @Param("now") Instant now);

    interface StockTotals {
        long getForSale();
        long getFree();
        long getOffers();
    }
}

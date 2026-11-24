package com.foodrescue.service;

import com.foodrescue.config.FoodRescueProperties;
import com.foodrescue.domain.FoodItem;
import com.foodrescue.domain.OfferStatus;
import com.foodrescue.domain.Order;
import com.foodrescue.domain.OrderStatus;
import com.foodrescue.domain.OrderType;
import com.foodrescue.domain.User;
import com.foodrescue.dto.NearbyFoodItemResponse;
import com.foodrescue.dto.OrderResponse;
import com.foodrescue.exception.BusinessRuleException;
import com.foodrescue.exception.ResourceNotFoundException;
import com.foodrescue.repository.FoodItemRepository;
import com.foodrescue.repository.OrderRepository;
import com.foodrescue.repository.UserRepository;
import com.foodrescue.domain.GeoPoint;
import com.foodrescue.util.GeoUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Core business logic for finding, reserving and claiming surplus food.
 *
 * <p>Controllers stay thin (HTTP only) and repositories stay dumb (SQL only). All rules live
 * here, which makes them easy to test without a web server or a database.</p>
 */
@Service
@RequiredArgsConstructor // Lombok generates a constructor for the final fields, Spring injects them.
@Slf4j
public class FoodItemService {

    private final FoodItemRepository foodItemRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final FoodRescueProperties properties;
    private final Clock clock;

    /** SecureRandom (not Random) because pickup codes should not be guessable. */
    private final SecureRandom secureRandom = new SecureRandom();

    // ------------------------------------------------------------------------------------
    // 1. Find available discounted food within a radius
    // ------------------------------------------------------------------------------------

    /**
     * Returns surplus food around a location, nearest first.
     *
     * <p>Algorithm in two steps:
     * <ol>
     *   <li><b>Coarse filter in SQL:</b> a bounding box around the user is calculated and the
     *       database returns only items whose store lies inside that rectangle. This uses an
     *       index, so it stays fast with many stores.</li>
     *   <li><b>Exact filter in Java:</b> the corners of the rectangle are farther away than the
     *       requested radius, so the Haversine distance is computed for each candidate and
     *       items outside the true circle are dropped.</li>
     * </ol>
     * Both steps together give a precise result without needing a spatial database. If the
     * platform grows to hundreds of thousands of stores, replace step one with a PostGIS
     * {@code ST_DWithin} query.</p>
     *
     * @param lat      latitude of the user in degrees
     * @param lng      longitude of the user in degrees
     * @param radiusKm search radius in kilometres
     */
    @Transactional(readOnly = true)
    public List<NearbyFoodItemResponse> findAvailableNearby(double lat, double lng, double radiusKm) {
        validateCoordinates(lat, lng);
        if (radiusKm <= 0 || radiusKm > properties.maxSearchRadiusKm()) {
            throw new BusinessRuleException(HttpStatus.BAD_REQUEST,
                    "radiusKm must be greater than 0 and at most " + properties.maxSearchRadiusKm());
        }

        Instant now = clock.instant();
        GeoUtils.BoundingBox box = GeoUtils.boundingBox(lat, lng, radiusKm);

        List<FoodItem> candidates = foodItemRepository.findAvailableInBoundingBox(
                now, box.minLat(), box.maxLat(), box.minLng(), box.maxLng());

        GeoPoint searchFrom = new GeoPoint(lat, lng);
        return candidates.stream()
                // Pair every item with its exact distance so it is calculated only once.
                .map(item -> new ItemWithDistance(item, searchFrom.distanceKmTo(item.getStore())))
                // Filter on the exact distance. Rounding for display happens later in the DTO,
                // so a shop at 3.04 km is correctly excluded from a 3 km search.
                .filter(candidate -> candidate.distanceKm() <= radiusKm)
                .sorted(Comparator.comparingDouble(ItemWithDistance::distanceKm)
                        .thenComparing(candidate -> candidate.item().getExpiresAt()))
                .map(candidate -> NearbyFoodItemResponse.from(candidate.item(), candidate.distanceKm()))
                .toList();
    }

    /** Internal pairing of an item with its exact distance from the user. */
    private record ItemWithDistance(FoodItem item, double distanceKm) {
    }

    // ------------------------------------------------------------------------------------
    // 2. Reserve a discounted item (paid)
    // ------------------------------------------------------------------------------------

    /**
     * Reserves units of an item at the discounted price. The consumer pays when collecting
     * (or through a payment provider added later); this method only secures the stock.
     *
     * <p>{@code @Transactional} means every database change below succeeds together or is
     * rolled back together. The row lock taken by {@code findByIdForUpdate} is released when
     * the method returns.</p>
     */
    @Transactional
    public OrderResponse reserve(Long userId, Long foodItemId, int quantity) {
        if (quantity < 1 || quantity > properties.maxQuantityPerReservation()) {
            throw new BusinessRuleException(HttpStatus.BAD_REQUEST,
                    "quantity must be between 1 and " + properties.maxQuantityPerReservation());
        }

        User user = loadUser(userId);
        FoodItem item = loadItemForUpdate(foodItemId);
        Instant now = clock.instant();

        assertNotExpired(item, now);
        if (item.getQuantityAvailable() < quantity) {
            throw new BusinessRuleException(HttpStatus.CONFLICT,
                    "Only " + item.getQuantityAvailable() + " unit(s) left");
        }

        // Reduce stock first, then record the order. Both are saved in the same transaction.
        item.setQuantityAvailable(item.getQuantityAvailable() - quantity);

        BigDecimal total = item.getDiscountedPrice().multiply(BigDecimal.valueOf(quantity));
        Order order = buildOrder(user, item, quantity, total, OrderType.PURCHASE, now);

        log.info("User {} reserved {} x item {}", userId, quantity, foodItemId);
        return OrderResponse.from(orderRepository.save(order));
    }

    // ------------------------------------------------------------------------------------
    // 3. Pay-It-Forward free claim
    // ------------------------------------------------------------------------------------

    /**
     * Lets an eligible person take one donated unit for free.
     *
     * <p>Pay-It-Forward works like a "suspended coffee": a store or a generous customer sets
     * units aside ({@code payItForwardQuantity}), and verified people in need can claim them
     * at no cost. Rules enforced here:</p>
     * <ol>
     *   <li>the user exists and has been verified as eligible by an administrator;</li>
     *   <li>the item has not expired and still has donated stock;</li>
     *   <li>the user has not exceeded the daily free claim limit, so one person cannot empty
     *       the pool.</li>
     * </ol>
     * Exactly one unit is claimed per call, and the resulting order has a total price of zero.
     */
    @Transactional
    public OrderResponse claimPayItForward(Long userId, Long foodItemId) {
        // Lock the user row before counting today's claims. Without it, two concurrent claims
        // for different items would both see the old count and both pass the daily limit.
        User user = loadUserForUpdate(userId);

        if (!user.isFreeClaimEligible()) {
            // 403: the request is valid, but this user is not allowed to do it.
            throw new BusinessRuleException(HttpStatus.FORBIDDEN,
                    "This account has not been verified for free Pay-It-Forward claims");
        }

        Instant now = clock.instant();

        // Count claims in the last 24 hours (rolling window, not calendar day).
        // Cancelled claims gave the unit back, so they do not use up the allowance.
        long claimsToday = orderRepository.countByUserIdAndTypeAndStatusNotAndCreatedAtAfter(
                userId, OrderType.PAY_IT_FORWARD, OrderStatus.CANCELLED, now.minus(Duration.ofHours(24)));
        if (claimsToday >= properties.payItForwardDailyLimit()) {
            throw new BusinessRuleException(HttpStatus.TOO_MANY_REQUESTS,
                    "Daily free claim limit of " + properties.payItForwardDailyLimit() + " reached");
        }

        FoodItem item = loadItemForUpdate(foodItemId);
        assertNotExpired(item, now);
        if (item.getPayItForwardQuantity() < 1) {
            throw new BusinessRuleException(HttpStatus.CONFLICT,
                    "No Pay-It-Forward units are left for this item");
        }

        item.setPayItForwardQuantity(item.getPayItForwardQuantity() - 1);

        Order order = buildOrder(user, item, 1, new BigDecimal("0.00"), OrderType.PAY_IT_FORWARD, now);

        log.info("User {} claimed a Pay-It-Forward unit of item {}", userId, foodItemId);
        return OrderResponse.from(orderRepository.save(order));
    }

    // ------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------

    private User loadUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
    }

    private User loadUserForUpdate(Long userId) {
        return userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
    }

    private FoodItem loadItemForUpdate(Long foodItemId) {
        return foodItemRepository.findByIdForUpdate(foodItemId)
                .orElseThrow(() -> new ResourceNotFoundException("Food item", foodItemId));
    }

    private void assertNotExpired(FoodItem item, Instant now) {
        if (item.isExpiredAt(now)) {
            // 410 Gone: the resource existed but is no longer available.
            throw new BusinessRuleException(HttpStatus.GONE, "This item has expired");
        }
        if (!item.getStore().isActive()) {
            throw new BusinessRuleException(HttpStatus.CONFLICT, "This store is currently not active");
        }
        // Drafts and paused offers are invisible in search; refuse them here too, for old links.
        if (item.getStatus() != OfferStatus.ACTIVE) {
            throw new BusinessRuleException(HttpStatus.CONFLICT, "This offer is not available right now");
        }
    }

    /**
     * Creates the order. With a pickup window set by the store (for example 18:00 to 20:00) the
     * reservation is held until the window ends. Without one, the deadline is the earlier of
     * "reservation window from now" and "item expiry". Either way a consumer is never told to
     * collect expired food.
     */
    private Order buildOrder(User user, FoodItem item, int quantity, BigDecimal total,
                             OrderType type, Instant now) {
        Instant windowEnd = now.plus(Duration.ofMinutes(properties.reservationWindowMinutes()));
        Instant deadline = item.getPickupStartsAt() != null || item.getExpiresAt().isBefore(windowEnd)
                ? item.getExpiresAt() : windowEnd;

        Order order = new Order();
        order.setUser(user);
        order.setFoodItem(item);
        order.setQuantity(quantity);
        order.setTotalPrice(total);
        order.setType(type);
        order.setStatus(OrderStatus.RESERVED);
        order.setPickupCode(generatePickupCode(item.getStore().getId()));
        order.setPickupDeadline(deadline);
        order.setCreatedAt(now);
        return order;
    }

    /**
     * Six random digits, for example "048213". Leading zeros are kept because it is a string.
     * The store looks a reservation up by this code alone, so it must not clash with another
     * open reservation of the same store.
     */
    private String generatePickupCode(Long storeId) {
        String code;
        do {
            code = String.format("%06d", secureRandom.nextInt(1_000_000));
        } while (orderRepository.existsByFoodItemStoreIdAndStatusAndPickupCode(storeId, OrderStatus.RESERVED, code));
        return code;
    }

    private void validateCoordinates(double lat, double lng) {
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            throw new BusinessRuleException(HttpStatus.BAD_REQUEST, "Invalid latitude or longitude");
        }
    }
}

package com.foodrescue.service;

import com.foodrescue.domain.FavouriteProduct;
import com.foodrescue.domain.FavouriteStore;
import com.foodrescue.domain.FoodTag;
import com.foodrescue.domain.NotificationKind;
import com.foodrescue.domain.ProductTemplate;
import com.foodrescue.domain.RadarProfile;
import com.foodrescue.domain.Store;
import com.foodrescue.dto.FavouritesResponse;
import com.foodrescue.dto.NearbyFoodItemResponse;
import com.foodrescue.dto.RadarProfileRequest;
import com.foodrescue.dto.RadarProfileResponse;
import com.foodrescue.dto.RadarResponse;
import com.foodrescue.exception.BusinessRuleException;
import com.foodrescue.exception.ResourceNotFoundException;
import com.foodrescue.repository.FavouriteProductRepository;
import com.foodrescue.repository.FavouriteStoreRepository;
import com.foodrescue.repository.NotificationRepository;
import com.foodrescue.repository.OrderRepository;
import com.foodrescue.repository.ProductTemplateRepository;
import com.foodrescue.repository.PushSubscriptionRepository;
import com.foodrescue.repository.RadarProfileRepository;
import com.foodrescue.repository.StoreRepository;
import com.foodrescue.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Rescue Radar: favourites, personal preferences and the "For you" recommendations.
 *
 * <p><b>How recommendations work.</b> Every offer near the customer gets a score from signals
 * the customer can see and understand: their favourites, the shops and products they collected
 * from before, the food types they are interested in, and then distance, discount and how soon
 * the offer ends. Only offers with at least one personal reason are picked, and each pick
 * carries its reasons ("You've collected from here 3 times"). No external service, no profile
 * shared with shops.</p>
 */
@Service
@RequiredArgsConstructor
public class RadarService {

    static final int MAX_PICKS = 6;
    static final Duration HISTORY = Duration.ofDays(120);
    static final int UNREAD_FOR_BACKOFF = 5;

    private final FoodItemService foodItemService;
    private final FavouriteStoreRepository favouriteStoreRepository;
    private final FavouriteProductRepository favouriteProductRepository;
    private final RadarProfileRepository profileRepository;
    private final StoreRepository storeRepository;
    private final ProductTemplateRepository templateRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final NotificationRepository notificationRepository;
    private final PushSubscriptionRepository pushSubscriptionRepository;
    private final Clock clock;

    // ------------------------------------------------------------------------------------
    // Favourites
    // ------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public FavouritesResponse favourites(Long userId) {
        List<FavouritesResponse.Store> stores = new ArrayList<>();
        for (FavouriteStore f : favouriteStoreRepository.findByUserIdOrderByCreatedAtDesc(userId)) {
            storeRepository.findById(f.getStoreId())
                    .ifPresent(s -> stores.add(new FavouritesResponse.Store(s.getId(), s.getName(), s.getAddress())));
        }
        List<FavouritesResponse.Product> products = new ArrayList<>();
        for (FavouriteProduct f : favouriteProductRepository.findByUserIdOrderByCreatedAtDesc(userId)) {
            templateRepository.findById(f.getTemplateId()).filter(t -> !t.isArchived()).ifPresent(t -> products.add(
                    new FavouritesResponse.Product(t.getId(), t.getName(), t.getStore().getId(), t.getStore().getName())));
        }
        return new FavouritesResponse(stores, products);
    }

    @Transactional
    public void addStore(Long userId, Long storeId) {
        Store store = storeRepository.findById(storeId).orElseThrow(() -> new ResourceNotFoundException("Store", storeId));
        if (!favouriteStoreRepository.existsById(new FavouriteStore.Key(userId, store.getId()))) {
            favouriteStoreRepository.save(new FavouriteStore(userId, store.getId(), clock.instant()));
        }
    }

    @Transactional
    public void removeStore(Long userId, Long storeId) {
        favouriteStoreRepository.deleteById(new FavouriteStore.Key(userId, storeId));
    }

    @Transactional
    public void addProduct(Long userId, Long templateId) {
        ProductTemplate t = templateRepository.findById(templateId)
                .filter(x -> !x.isArchived())
                .orElseThrow(() -> new ResourceNotFoundException("Product", templateId));
        if (!favouriteProductRepository.existsById(new FavouriteProduct.Key(userId, t.getId()))) {
            favouriteProductRepository.save(new FavouriteProduct(userId, t.getId(), clock.instant()));
        }
    }

    @Transactional
    public void removeProduct(Long userId, Long templateId) {
        favouriteProductRepository.deleteById(new FavouriteProduct.Key(userId, templateId));
    }

    // ------------------------------------------------------------------------------------
    // Preferences
    // ------------------------------------------------------------------------------------

    /** The stored profile, or the defaults when the customer never saved one. */
    RadarProfile profileOf(Long userId) {
        return profileRepository.findById(userId).orElseGet(() -> {
            RadarProfile p = new RadarProfile();
            p.setUserId(userId);
            return p;
        });
    }

    @Transactional(readOnly = true)
    public RadarProfileResponse profile(Long userId) {
        return toResponse(profileOf(userId));
    }

    @Transactional
    public RadarProfileResponse saveProfile(Long userId, RadarProfileRequest request) {
        if ((request.quietStart() == null) != (request.quietEnd() == null)) {
            throw new BusinessRuleException(HttpStatus.BAD_REQUEST, "Choose both a start and an end for quiet hours");
        }
        String zone = request.timeZone().trim();
        try {
            if (!zone.contains("/") && !zone.equals("UTC")) throw new DateTimeException(zone);
            ZoneId.of(zone);
        } catch (DateTimeException e) {
            throw new BusinessRuleException(HttpStatus.BAD_REQUEST, "Unknown time zone: " + zone);
        }
        RadarProfile p = profileOf(userId);
        p.setPersonalised(request.personalised());
        p.setInterests(request.interests() == null || request.interests().isEmpty()
                ? EnumSet.noneOf(FoodTag.class) : EnumSet.copyOf(request.interests()));
        p.setNotifyFavourites(request.notifyFavourites());
        p.setQuietStart(request.quietStart());
        p.setQuietEnd(request.quietEnd());
        if (request.digestTime() == null || !request.digestTime().equals(p.getDigestTime())) {
            // A new digest time may fire again today.
            p.setLastDigestOn(null);
        }
        p.setDigestTime(request.digestTime());
        p.setTimeZone(zone);
        if (request.pickupReminders() != null) p.setPickupReminders(request.pickupReminders());
        if (request.freeItemsOffer() != null) p.setFreeItemsOffer(request.freeItemsOffer());
        p.setUpdatedAt(clock.instant());
        return toResponse(profileRepository.save(p));
    }

    /** "Don't ask again" on the free-items offer, or turning it back on, without touching the other settings. */
    @Transactional
    public RadarProfileResponse setFreeItemsOffer(Long userId, boolean show) {
        RadarProfile p = profileOf(userId);
        p.setFreeItemsOffer(show);
        p.setUpdatedAt(clock.instant());
        return toResponse(profileRepository.save(p));
    }

    private RadarProfileResponse toResponse(RadarProfile p) {
        return new RadarProfileResponse(p.isPersonalised(), p.getInterests(), p.isNotifyFavourites(), p.getQuietStart(),
                p.getQuietEnd(), p.getDigestTime(), p.getTimeZone(), throttled(List.of(p.getUserId())).contains(p.getUserId()),
                pushSubscriptionRepository.countByUserId(p.getUserId()), p.isPickupReminders(), p.isFreeItemsOffer());
    }

    /**
     * Customers who left at least {@value #UNREAD_FOR_BACKOFF} alerts of the last week unopened.
     * They get fewer alerts until they open the app again.
     */
    Set<Long> throttled(List<Long> userIds) {
        Set<Long> result = new java.util.HashSet<>();
        if (userIds.isEmpty()) return result;
        for (Object[] row : notificationRepository.unreadSince(userIds, clock.instant().minus(Duration.ofDays(7)),
                List.of(NotificationKind.DROP, NotificationKind.FAVOURITE))) {
            if (((Long) row[1]) >= UNREAD_FOR_BACKOFF) result.add((Long) row[0]);
        }
        return result;
    }

    // ------------------------------------------------------------------------------------
    // Recommendations
    // ------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public RadarResponse recommend(Long userId, double lat, double lng, double radiusKm) {
        RadarProfile profile = profileOf(userId);
        Set<Long> favStores = favouriteStoreRepository.storeIdsOf(userId);
        Set<Long> favProducts = favouriteProductRepository.templateIdsOf(userId);
        if (!profile.isPersonalised()) {
            return new RadarResponse(false, List.of(), favStores, favProducts);
        }
        List<NearbyFoodItemResponse> nearby = foodItemService.findAvailableNearby(lat, lng, radiusKm);
        return new RadarResponse(true, pick(nearby, signals(userId, profile, favStores, favProducts), clock.instant()),
                favStores, favProducts);
    }

    /** Plain search around a place (used by the digest for names). */
    @Transactional(readOnly = true)
    public List<NearbyFoodItemResponse> nearby(double lat, double lng, double radiusKm) {
        return foodItemService.findAvailableNearby(lat, lng, radiusKm);
    }

    /** Everything the scoring needs to know about one customer. */
    record Signals(Set<Long> favStores, Set<Long> favProducts, Map<Long, Integer> storeVisits,
                   Map<Long, Integer> productVisits, Set<FoodTag> interests, boolean freeEligible) {
    }

    Signals signals(Long userId, RadarProfile profile, Set<Long> favStores, Set<Long> favProducts) {
        Map<Long, Integer> storeVisits = new HashMap<>(), productVisits = new HashMap<>();
        for (Object[] row : orderRepository.collectedBy(userId, clock.instant().minus(HISTORY))) {
            int n = ((Number) row[2]).intValue();
            storeVisits.merge((Long) row[0], n, Integer::sum);
            if (row[1] != null) productVisits.merge((Long) row[1], n, Integer::sum);
        }
        boolean eligible = userRepository.findById(userId).map(u -> u.isFreeClaimEligible()).orElse(false);
        return new Signals(favStores, favProducts, storeVisits, productVisits, profile.getInterests(), eligible);
    }

    static List<RadarResponse.Pick> pick(List<NearbyFoodItemResponse> items, Signals s, Instant now) {
        return items.stream().map(i -> score(i, s, now)).filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparingInt(RadarResponse.Pick::score).reversed())
                .limit(MAX_PICKS).toList();
    }

    /**
     * Scores one offer, or returns null when nothing about it is personal to this customer.
     * Reasons are listed strongest first.
     */
    static RadarResponse.Pick score(NearbyFoodItemResponse i, Signals s, Instant now) {
        record Reason(int points, String text) {
        }
        List<Reason> personal = new ArrayList<>();
        if (i.templateId() != null && s.favProducts().contains(i.templateId())) {
            personal.add(new Reason(40, "One of your favourites"));
        }
        if (s.favStores().contains(i.storeId())) {
            personal.add(new Reason(25, "From a shop you follow"));
        }
        int productVisits = i.templateId() == null ? 0 : s.productVisits().getOrDefault(i.templateId(), 0);
        int storeVisits = s.storeVisits().getOrDefault(i.storeId(), 0);
        if (productVisits > 0) {
            personal.add(new Reason(10 + 3 * Math.min(productVisits, 5),
                    "You've had this " + (productVisits == 1 ? "once" : productVisits + " times") + " before"));
        } else if (storeVisits > 0) {
            personal.add(new Reason(4 * Math.min(storeVisits, 5),
                    "You've collected from here " + (storeVisits == 1 ? "once" : storeVisits + " times")));
        }
        List<FoodTag> matches = i.tags() == null ? List.of() : i.tags().stream().filter(s.interests()::contains).toList();
        if (!matches.isEmpty()) {
            personal.add(new Reason(15, "Matches your interest in " + label(matches.get(0))));
        }
        if (personal.isEmpty()) {
            return null;
        }

        List<Reason> extra = new ArrayList<>();
        if (s.freeEligible() && i.payItForwardQuantity() > 0) extra.add(new Reason(10, "Free units available for you"));
        if (i.rescueDrop()) extra.add(new Reason(8, "Live Rescue Drop"));
        long minutesLeft = Duration.between(now, i.expiresAt()).toMinutes();
        if (minutesLeft <= 90) extra.add(new Reason(5, "Ends soon"));

        int score = personal.stream().mapToInt(Reason::points).sum() + extra.stream().mapToInt(Reason::points).sum()
                + Math.min(20, i.discountPercent() / 4)
                - (int) Math.round(i.distanceKm() * 4);
        List<String> reasons = new ArrayList<>();
        personal.stream().sorted(Comparator.comparingInt(Reason::points).reversed()).limit(2).forEach(r -> reasons.add(r.text()));
        extra.stream().limit(1).forEach(r -> reasons.add(r.text()));
        return new RadarResponse.Pick(i.id(), score, reasons);
    }

    static String label(FoodTag tag) {
        return switch (tag) {
            case FRUIT_VEG -> "fruit & veg";
            case READY_MEALS -> "ready meals";
            case MEAT_FISH -> "meat & fish";
            default -> tag.name().toLowerCase(Locale.ROOT);
        };
    }
}

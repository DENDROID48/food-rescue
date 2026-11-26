package com.foodrescue.config;

import com.foodrescue.domain.DropAlertPreference;
import com.foodrescue.domain.FavouriteProduct;
import com.foodrescue.domain.FoodTag;
import com.foodrescue.domain.RadarProfile;
import com.foodrescue.domain.EligibilityStatus;
import com.foodrescue.domain.Order;
import com.foodrescue.domain.OrderStatus;
import com.foodrescue.domain.OrderType;
import com.foodrescue.domain.OfferKind;
import com.foodrescue.domain.ProductTemplate;
import com.foodrescue.domain.FoodItem;
import com.foodrescue.domain.Store;
import com.foodrescue.domain.User;
import com.foodrescue.domain.UserRole;
import com.foodrescue.repository.DropAlertPreferenceRepository;
import com.foodrescue.repository.FavouriteProductRepository;
import com.foodrescue.repository.FoodItemRepository;
import com.foodrescue.repository.RadarProfileRepository;
import com.foodrescue.repository.OrderRepository;
import com.foodrescue.repository.ProductTemplateRepository;
import com.foodrescue.repository.StoreRepository;
import com.foodrescue.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.List;

/**
 * Inserts a few rows at startup so the API can be tried immediately.
 * Only active in the "dev" profile, so it never touches a production database.
 * The coordinates are in central Timisoara, Romania.
 *
 * <p>Every demo account uses the password {@value #DEMO_PASSWORD}.</p>
 */
@Component
@Profile("dev")
@RequiredArgsConstructor
@Slf4j
public class DevDataSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final StoreRepository storeRepository;
    private final FoodItemRepository foodItemRepository;
    private final ProductTemplateRepository templateRepository;
    private final OrderRepository orderRepository;
    private final DropAlertPreferenceRepository dropAlertPreferenceRepository;
    private final FavouriteProductRepository favouriteProductRepository;
    private final RadarProfileRepository radarProfileRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    static final String DEMO_PASSWORD = "demo1234";

    @Override
    public void run(String... args) {
        newUser("Platform Admin", "admin@example.com", UserRole.ADMIN, EligibilityStatus.NOT_REQUESTED);
        User owner = newUser("Bakery Owner", "owner@example.com", UserRole.STORE_OWNER, EligibilityStatus.NOT_REQUESTED);
        newUser("Test Student", "student@example.com", UserRole.CONSUMER, EligibilityStatus.NOT_REQUESTED);
        newUser("Verified Beneficiary", "beneficiary@example.com", UserRole.CONSUMER, EligibilityStatus.APPROVED);
        // Gives the admin a verification request to decide on.
        newUser("Pending Applicant", "applicant@example.com", UserRole.CONSUMER, EligibilityStatus.PENDING);
        // Shows the no-show rule: three missed pickups, so one reservation at a time for now.
        User late = newUser("Late Larry", "late@example.com", UserRole.CONSUMER, EligibilityStatus.NOT_REQUESTED);

        Store bakery = newStore("Corner Bakery", "Piata Victoriei 1, Timisoara", 45.7537, 21.2257, owner);
        Store market = newStore("Neighbourhood Market", "Bulevardul Take Ionescu 10, Timisoara", 45.7489, 21.2260, owner);
        Store faraway = newStore("Distant Shop", "Arad", 46.1866, 21.3123, owner);

        Instant now = clock.instant();
        newItem(bakery, "Assorted pastries bag", "10 pastries from today", "30.00", "9.00", 6, 2, now.plus(Duration.ofHours(4)));
        newItem(market, "Fresh vegetable box", "Mixed seasonal vegetables", "25.00", "10.00", 3, 1, now.plus(Duration.ofHours(8)));
        newItem(faraway, "Sandwich pack", "Out of range sample", "20.00", "8.00", 4, 0, now.plus(Duration.ofHours(5)));

        // Reusable products for the daily "Set up today's offers" flow. They carry no quantity.
        ProductTemplate bag = newTemplate(bakery, "Bakery surprise bag", "A mix of today's bread and pastries", OfferKind.SURPRISE_BAG, "30.00", "10.00", 18, 20);
        ProductTemplate sourdough = newTemplate(bakery, "Sourdough loaf", "Baked this morning", OfferKind.PRODUCT, "14.00", "6.00", 17, 20);
        ProductTemplate croissants = newTemplate(bakery, "Croissants, box of 4", "Butter croissants", OfferKind.PRODUCT, "16.00", "6.50", 15, 19);
        ProductTemplate vegBox = newTemplate(market, "Vegetable box", "Seasonal vegetables, a little less pretty", OfferKind.SURPRISE_BAG, "25.00", "10.00", 17, 20);
        // Approximate demo weights per unit, for "kg rescued" in the analytics.
        bag.setUnitWeightGrams(1500);
        sourdough.setUnitWeightGrams(800);
        croissants.setUnitWeightGrams(280);
        vegBox.setUnitWeightGrams(3000); // saved below, together with the food types
        bakery.setReminderTime(LocalTime.of(9, 0));
        storeRepository.save(bakery);

        // Two weeks of past days, so the Autopilot has history to learn from in the demo:
        // sourdough usually sells out, croissants often go unsold, the bag sells steadily.
        User student = userRepository.findByEmail("student@example.com").orElseThrow();
        ZoneId zone = bakery.zone();
        LocalDate today = LocalDate.now(clock.withZone(zone));
        int code = 100000;
        for (int daysAgo = 1; daysAgo <= 14; daysAgo++) {
            LocalDate day = today.minusDays(daysAgo);
            boolean weekend = day.getDayOfWeek().getValue() >= 6;
            code = pastDay(sourdough, day, zone, weekend ? 8 : 6, weekend ? 8 : 6, student, code);
            code = pastDay(croissants, day, zone, 8, 3 + daysAgo % 2, student, code);
            code = pastDay(bag, day, zone, 5, 4, student, code);
        }

        // Late Larry's three missed pickups, at the shop in Arad so the bakery's figures stay as they are.
        for (int daysAgo : new int[]{2, 6, 12}) code = missedPickup(faraway, late, today.minusDays(daysAgo), zone, code);

        // The test student gets Rescue Drop alerts around the city centre.
        DropAlertPreference alerts = new DropAlertPreference();
        alerts.setUserId(student.getId());
        alerts.setEnabled(true);
        alerts.setLatitude(45.7540);
        alerts.setLongitude(21.2250);
        alerts.setRadiusKm(3);
        alerts.setMaxPerDay(3);
        alerts.setUpdatedAt(now);
        dropAlertPreferenceRepository.save(alerts);

        // Rescue Radar: food types on the demo products, and a student who likes bakery food,
        // follows the sourdough and wants to hear about favourites.
        bag.setTags(EnumSet.of(FoodTag.BAKERY));
        sourdough.setTags(EnumSet.of(FoodTag.BAKERY));
        croissants.setTags(EnumSet.of(FoodTag.BAKERY));
        templateRepository.saveAll(List.of(bag, sourdough, croissants));
        vegBox.setTags(EnumSet.of(FoodTag.FRUIT_VEG));
        templateRepository.save(vegBox);
        favouriteProductRepository.save(new FavouriteProduct(student.getId(), sourdough.getId(), now));
        RadarProfile profile = new RadarProfile();
        profile.setUserId(student.getId());
        profile.setInterests(EnumSet.of(FoodTag.BAKERY));
        profile.setNotifyFavourites(true);
        profile.setUpdatedAt(now);
        radarProfileRepository.save(profile);

        log.info("Seeded sample data. Demo logins (password \"{}\"): admin@example.com, owner@example.com, "
                + "student@example.com, beneficiary@example.com, applicant@example.com, late@example.com", DEMO_PASSWORD);
    }

    private User newUser(String name, String email, UserRole role, EligibilityStatus eligibility) {
        User user = new User();
        user.setFullName(name);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(DEMO_PASSWORD));
        user.setRole(role);
        user.setEligibilityStatus(eligibility);
        return userRepository.save(user);
    }

    private Store newStore(String name, String address, double lat, double lng, User owner) {
        Store store = new Store();
        store.setName(name);
        store.setAddress(address);
        store.setLatitude(lat);
        store.setLongitude(lng);
        store.setOwner(owner);
        return storeRepository.save(store);
    }

    /** One finished past day of a product: units offered, units collected, the rest left over. */
    private int pastDay(ProductTemplate t, LocalDate day, ZoneId zone, int offered, int collected, User customer, int code) {
        FoodItem item = new FoodItem();
        item.setStore(t.getStore());
        item.setTemplate(t);
        item.setName(t.getName());
        item.setDescription(t.getDescription());
        item.setKind(t.getKind());
        item.setOriginalPrice(t.getOriginalPrice());
        item.setDiscountedPrice(t.getDiscountedPrice());
        item.setQuantityAvailable(offered - collected);
        item.setQuantityPublished(offered);
        item.setFreePublished(0);
        item.setUnitWeightGrams(t.getUnitWeightGrams());
        item.setPickupStartsAt(ZonedDateTime.of(day, t.getPickupStart(), zone).toInstant());
        item.setExpiresAt(ZonedDateTime.of(day, t.getPickupEnd(), zone).toInstant());
        item.setCreatedAt(ZonedDateTime.of(day, LocalTime.of(9, 0), zone).toInstant());
        item = foodItemRepository.save(item);
        for (int left = collected; left > 0; left -= 2) {
            int units = Math.min(2, left);
            Order order = new Order();
            order.setUser(customer);
            order.setFoodItem(item);
            order.setQuantity(units);
            order.setTotalPrice(t.getDiscountedPrice().multiply(BigDecimal.valueOf(units)));
            order.setType(OrderType.PURCHASE);
            order.setStatus(OrderStatus.PICKED_UP);
            order.setPickupCode(String.valueOf(code++));
            order.setPickupDeadline(item.getExpiresAt());
            // Reservations come in through the afternoon, the last ones close to pickup.
            order.setCreatedAt(item.getPickupStartsAt().minus(Duration.ofMinutes(150)).plus(Duration.ofMinutes(25L * (collected - left))));
            order.setPickedUpAt(item.getPickupStartsAt().plus(Duration.ofMinutes(30)));
            orderRepository.save(order);
        }
        return code;
    }

    /** A reservation whose pickup time ended without the customer coming. */
    private int missedPickup(Store store, User customer, LocalDate day, ZoneId zone, int code) {
        FoodItem item = new FoodItem();
        item.setStore(store);
        item.setName("Sandwich pack");
        item.setDescription("Out of range sample");
        item.setOriginalPrice(new BigDecimal("20.00"));
        item.setDiscountedPrice(new BigDecimal("8.00"));
        item.setQuantityAvailable(0);
        item.setQuantityPublished(1);
        item.setFreePublished(0);
        item.setPickupStartsAt(ZonedDateTime.of(day, LocalTime.of(17, 0), zone).toInstant());
        item.setExpiresAt(ZonedDateTime.of(day, LocalTime.of(19, 0), zone).toInstant());
        item.setCreatedAt(ZonedDateTime.of(day, LocalTime.of(9, 0), zone).toInstant());
        item = foodItemRepository.save(item);
        Order order = new Order();
        order.setUser(customer);
        order.setFoodItem(item);
        order.setQuantity(1);
        order.setTotalPrice(item.getDiscountedPrice());
        order.setType(OrderType.PURCHASE);
        order.setStatus(OrderStatus.EXPIRED);
        order.setPickupCode(String.valueOf(code++));
        order.setPickupDeadline(item.getExpiresAt());
        order.setCreatedAt(item.getPickupStartsAt().minus(Duration.ofHours(1)));
        orderRepository.save(order);
        return code;
    }

    private ProductTemplate newTemplate(Store store, String name, String description, OfferKind kind,
                             String original, String discounted, int fromHour, int toHour) {
        ProductTemplate t = new ProductTemplate();
        t.setStore(store);
        t.setName(name);
        t.setDescription(description);
        t.setKind(kind);
        t.setOriginalPrice(new BigDecimal(original));
        t.setDiscountedPrice(new BigDecimal(discounted));
        t.setPickupStart(LocalTime.of(fromHour, 0));
        t.setPickupEnd(LocalTime.of(toHour, 0));
        return templateRepository.save(t);
    }

    private void newItem(Store store, String name, String description, String original, String discounted,
                         int quantity, int payItForward, Instant expiresAt) {
        FoodItem item = new FoodItem();
        item.setStore(store);
        item.setName(name);
        item.setDescription(description);
        item.setOriginalPrice(new BigDecimal(original));
        item.setDiscountedPrice(new BigDecimal(discounted));
        item.setQuantityAvailable(quantity);
        item.setPayItForwardQuantity(payItForward);
        item.setExpiresAt(expiresAt);
        foodItemRepository.save(item);
    }
}

package com.foodrescue.config;

import com.foodrescue.domain.ConsumerCategory;
import com.foodrescue.domain.EligibilityStatus;
import com.foodrescue.domain.FoodItem;
import com.foodrescue.domain.Store;
import com.foodrescue.domain.User;
import com.foodrescue.domain.UserRole;
import com.foodrescue.repository.FoodItemRepository;
import com.foodrescue.repository.StoreRepository;
import com.foodrescue.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Inserts a few rows at startup so the API can be tried immediately.
 * Only active in the "dev" profile, so it never touches a production database.
 * The coordinates are in central Timisoara, Romania.
 *
 * <p>Every demo account uses the password {@value #DEMO_PASSWORD}.</p>
 */
@Component
@Profile("dev")
@Order(10) // After FileDataStore has loaded any saved data.
@RequiredArgsConstructor
@Slf4j
public class DevDataSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final StoreRepository storeRepository;
    private final FoodItemRepository foodItemRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    static final String DEMO_PASSWORD = "demo1234";

    @Override
    public void run(String... args) {
        if (userRepository.count() > 0) {
            log.info("Data was loaded from the data files, so no demo data is added");
            return;
        }
        newUser("Platform Admin", "admin@example.com", UserRole.ADMIN, EligibilityStatus.NOT_REQUESTED);
        User owner = newUser("Bakery Owner", "owner@example.com", UserRole.STORE_OWNER, EligibilityStatus.NOT_REQUESTED);
        newUser("Test Student", "student@example.com", UserRole.CONSUMER, EligibilityStatus.NOT_REQUESTED);
        User verified = newUser("Verified Beneficiary", "beneficiary@example.com", UserRole.CONSUMER, EligibilityStatus.APPROVED);
        verified.setCategory(ConsumerCategory.IN_NEED);
        userRepository.save(verified);
        // Gives the admin a verification request to decide on.
        newUser("Pending Applicant", "applicant@example.com", UserRole.CONSUMER, EligibilityStatus.PENDING);

        Store bakery = newStore("Corner Bakery", "Piata Victoriei 1, Timisoara", 45.7537, 21.2257, owner);
        Store market = newStore("Neighbourhood Market", "Bulevardul Take Ionescu 10, Timisoara", 45.7489, 21.2260, owner);
        Store faraway = newStore("Distant Shop", "Arad", 46.1866, 21.3123, owner);

        Instant now = clock.instant();
        newItem(bakery, "Assorted pastries bag", "10 pastries from today", "30.00", "9.00", 6, 2, now.plus(Duration.ofHours(4)));
        newItem(market, "Fresh vegetable box", "Mixed seasonal vegetables", "25.00", "10.00", 3, 1, now.plus(Duration.ofHours(8)));
        newItem(faraway, "Sandwich pack", "Out of range sample", "20.00", "8.00", 4, 0, now.plus(Duration.ofHours(5)));

        log.info("Seeded sample data. Demo logins (password \"{}\"): admin@example.com, owner@example.com, "
                + "student@example.com, beneficiary@example.com, applicant@example.com", DEMO_PASSWORD);
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

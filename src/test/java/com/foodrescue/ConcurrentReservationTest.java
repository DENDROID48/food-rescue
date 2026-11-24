package com.foodrescue;

import com.foodrescue.domain.FoodItem;
import com.foodrescue.domain.OfferStatus;
import com.foodrescue.domain.Store;
import com.foodrescue.domain.User;
import com.foodrescue.domain.UserRole;
import com.foodrescue.exception.BusinessRuleException;
import com.foodrescue.repository.FoodItemRepository;
import com.foodrescue.repository.OrderRepository;
import com.foodrescue.repository.StoreRepository;
import com.foodrescue.repository.UserRepository;
import com.foodrescue.service.FoodItemService;
import com.foodrescue.service.GeocodingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Many customers press "Reserve" on the last units at the same moment, against the real
 * database. The row lock must let exactly as many through as there are units.
 */
// Same configuration as the other integration tests, so they share one application context.
@SpringBootTest(properties = "foodrescue.geocoding.enabled=false")
@AutoConfigureMockMvc
class ConcurrentReservationTest {

    @Autowired FoodItemService foodItemService;
    @Autowired FoodItemRepository foodItemRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired UserRepository userRepository;
    @MockitoBean GeocodingService geocodingService;

    private User user(UserRole role) {
        User u = new User();
        u.setFullName("Racer");
        u.setEmail("racer-" + UUID.randomUUID() + "@test.ro");
        u.setPasswordHash("x");
        u.setRole(role);
        return userRepository.save(u);
    }

    @Test
    void simultaneousReservationsNeverOversell() throws Exception {
        Store store = new Store();
        store.setName("Race Shop");
        store.setAddress("Strada 3");
        store.setLatitude(12.0);
        store.setLongitude(12.0);
        store.setOwner(user(UserRole.STORE_OWNER));
        store = storeRepository.save(store);

        FoodItem item = new FoodItem();
        item.setStore(store);
        item.setName("Last loaves");
        item.setOriginalPrice(new BigDecimal("10.00"));
        item.setDiscountedPrice(new BigDecimal("4.00"));
        item.setQuantityAvailable(3);
        item.setStatus(OfferStatus.ACTIVE);
        item.setExpiresAt(Instant.now().plus(Duration.ofHours(2)));
        long itemId = foodItemRepository.save(item).getId();

        int customers = 8;
        List<Long> userIds = new ArrayList<>();
        for (int i = 0; i < customers; i++) userIds.add(user(UserRole.CONSUMER).getId());

        ExecutorService pool = Executors.newFixedThreadPool(customers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (Long userId : userIds) {
            results.add(pool.submit(() -> {
                start.await();
                try {
                    foodItemService.reserve(userId, itemId, 1);
                    return true;
                } catch (BusinessRuleException e) {
                    return false; // "Only 0 unit(s) left"
                }
            }));
        }
        start.countDown();
        int succeeded = 0;
        for (Future<Boolean> r : results) if (r.get(30, TimeUnit.SECONDS)) succeeded++;
        pool.shutdown();

        assertThat(succeeded).isEqualTo(3);
        assertThat(foodItemRepository.findById(itemId).orElseThrow().getQuantityAvailable()).isZero();
        assertThat(orderRepository.findAll().stream().filter(o -> o.getFoodItem().getId().equals(itemId)).count()).isEqualTo(3);
    }
}

package com.foodrescue.service;

import com.foodrescue.config.FoodRescueProperties;
import com.foodrescue.domain.EligibilityStatus;
import com.foodrescue.domain.OfferStatus;
import com.foodrescue.domain.FoodItem;
import com.foodrescue.domain.Order;
import com.foodrescue.domain.OrderType;
import com.foodrescue.domain.Store;
import com.foodrescue.domain.User;
import com.foodrescue.dto.OrderResponse;
import com.foodrescue.exception.BusinessRuleException;
import com.foodrescue.repository.FoodItemRepository;
import com.foodrescue.repository.OrderRepository;
import com.foodrescue.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests: repositories are mocked, so no database or web server is started.
 * Time is frozen with a fixed Clock.
 */
@ExtendWith(MockitoExtension.class)
class FoodItemServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");

    @Mock FoodItemRepository foodItemRepository;
    @Mock OrderRepository orderRepository;
    @Mock UserRepository userRepository;

    FoodItemService service;
    User user;
    FoodItem item;

    @BeforeEach
    void setUp() {
        FoodRescueProperties props = new FoodRescueProperties(25, 60, 1, 5);
        service = new FoodItemService(foodItemRepository, orderRepository, userRepository,
                props, Clock.fixed(NOW, ZoneOffset.UTC));

        user = new User();
        user.setEligibilityStatus(EligibilityStatus.APPROVED);

        Store store = new Store();
        store.setName("Test Bakery");
        store.setAddress("Main Street 1");
        store.setActive(true);

        item = new FoodItem();
        item.setName("Bread");
        item.setStore(store);
        item.setOriginalPrice(new BigDecimal("10.00"));
        item.setDiscountedPrice(new BigDecimal("4.00"));
        item.setQuantityAvailable(3);
        item.setPayItForwardQuantity(1);
        item.setExpiresAt(NOW.plus(Duration.ofHours(3)));

        // Return the order unchanged, like a real save would.
        org.mockito.Mockito.lenient().when(orderRepository.save(any(Order.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void givenUserAndItemExist() {
        when(userRepository.findById(anyLong())).thenReturn(Optional.of(user));
        when(foodItemRepository.findByIdForUpdate(anyLong())).thenReturn(Optional.of(item));
    }

    @Test
    void reserveReducesStockAndChargesDiscountedPrice() {
        givenUserAndItemExist();

        OrderResponse response = service.reserve(1L, 1L, 2);

        assertThat(item.getQuantityAvailable()).isEqualTo(1);
        assertThat(response.totalPrice()).isEqualByComparingTo("8.00");
        assertThat(response.type()).isEqualTo(OrderType.PURCHASE);
        assertThat(response.pickupCode()).hasSize(6);
    }

    @Test
    void reserveFailsWhenNotEnoughStock() {
        givenUserAndItemExist();

        assertThatThrownBy(() -> service.reserve(1L, 1L, 4))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("left");
    }

    @Test
    void reserveFailsWhenItemExpired() {
        givenUserAndItemExist();
        item.setExpiresAt(NOW.minusSeconds(1));

        assertThatThrownBy(() -> service.reserve(1L, 1L, 1))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void pausedAndDraftOffersCannotBeReservedOrClaimed() {
        when(userRepository.findById(anyLong())).thenReturn(Optional.of(user));
        when(userRepository.findByIdForUpdate(anyLong())).thenReturn(Optional.of(user));
        when(foodItemRepository.findByIdForUpdate(anyLong())).thenReturn(Optional.of(item));
        for (OfferStatus status : new OfferStatus[]{OfferStatus.PAUSED, OfferStatus.DRAFT}) {
            item.setStatus(status);
            assertThatThrownBy(() -> service.reserve(1L, 1L, 1)).hasMessageContaining("not available");
            assertThatThrownBy(() -> service.claimPayItForward(1L, 1L)).hasMessageContaining("not available");
        }
        assertThat(item.getQuantityAvailable()).isEqualTo(3);
        assertThat(item.getPayItForwardQuantity()).isEqualTo(1);
    }

    @Test
    void reservationIsHeldUntilTheEndOfThePickupWindow() {
        givenUserAndItemExist();
        item.setPickupStartsAt(NOW.plus(Duration.ofHours(2)));
        item.setExpiresAt(NOW.plus(Duration.ofHours(4)));

        OrderResponse response = service.reserve(1L, 1L, 1);

        // Not "60 minutes from now", which would end before the window even opens.
        assertThat(response.pickupDeadline()).isEqualTo(NOW.plus(Duration.ofHours(4)));
        assertThat(response.pickupOpensAt()).isEqualTo(NOW.plus(Duration.ofHours(2)));
    }

    private void givenLockedUserAndItemExist() {
        when(userRepository.findByIdForUpdate(anyLong())).thenReturn(Optional.of(user));
        when(foodItemRepository.findByIdForUpdate(anyLong())).thenReturn(Optional.of(item));
    }

    @Test
    void freeClaimGivesOneUnitForZeroPrice() {
        givenLockedUserAndItemExist();
        when(orderRepository.countByUserIdAndTypeAndStatusNotAndCreatedAtAfter(anyLong(), eq(OrderType.PAY_IT_FORWARD), any(), any()))
                .thenReturn(0L);

        OrderResponse response = service.claimPayItForward(1L, 1L);

        assertThat(item.getPayItForwardQuantity()).isZero();
        assertThat(response.totalPrice()).isEqualByComparingTo("0.00");
        assertThat(response.type()).isEqualTo(OrderType.PAY_IT_FORWARD);
    }

    @Test
    void freeClaimRejectedForUnverifiedUser() {
        user.setEligibilityStatus(EligibilityStatus.NOT_REQUESTED);
        when(userRepository.findByIdForUpdate(anyLong())).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.claimPayItForward(1L, 1L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("verified");
    }

    @Test
    void freeClaimRejectedWhenDailyLimitReached() {
        when(userRepository.findByIdForUpdate(anyLong())).thenReturn(Optional.of(user));
        when(orderRepository.countByUserIdAndTypeAndStatusNotAndCreatedAtAfter(anyLong(), eq(OrderType.PAY_IT_FORWARD), any(), any()))
                .thenReturn(1L);

        assertThatThrownBy(() -> service.claimPayItForward(1L, 1L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("limit");
    }
}

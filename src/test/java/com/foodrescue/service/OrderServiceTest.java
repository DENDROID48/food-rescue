package com.foodrescue.service;

import com.foodrescue.domain.FoodItem;
import com.foodrescue.domain.Order;
import com.foodrescue.domain.OrderStatus;
import com.foodrescue.domain.OrderType;
import com.foodrescue.domain.Store;
import com.foodrescue.domain.User;
import com.foodrescue.dto.OrderResponse;
import com.foodrescue.exception.BusinessRuleException;
import com.foodrescue.exception.ResourceNotFoundException;
import com.foodrescue.repository.FoodItemRepository;
import com.foodrescue.repository.OrderRepository;
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
import static org.mockito.Mockito.when;

/** Pure unit tests for cancelling and collecting orders, with mocked repositories and a frozen clock. */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");

    @Mock OrderRepository orderRepository;
    @Mock FoodItemRepository foodItemRepository;
    @Mock StoreService storeService;

    OrderService service;
    User customer;
    FoodItem item;
    Order order;

    @BeforeEach
    void setUp() {
        service = new OrderService(orderRepository, foodItemRepository, storeService, Clock.fixed(NOW, ZoneOffset.UTC));

        customer = new User();
        customer.setId(7L);

        Store store = new Store();
        store.setId(1L);
        store.setName("Test Bakery");
        store.setAddress("Main Street 1");

        item = new FoodItem();
        item.setId(3L);
        item.setName("Bread");
        item.setStore(store);
        item.setOriginalPrice(new BigDecimal("10.00"));
        item.setDiscountedPrice(new BigDecimal("4.00"));
        item.setQuantityAvailable(1);
        item.setPayItForwardQuantity(0);
        item.setExpiresAt(NOW.plus(Duration.ofHours(3)));

        order = new Order();
        order.setId(42L);
        order.setUser(customer);
        order.setFoodItem(item);
        order.setQuantity(2);
        order.setTotalPrice(new BigDecimal("8.00"));
        order.setType(OrderType.PURCHASE);
        order.setStatus(OrderStatus.RESERVED);
        order.setPickupCode("123456");
        order.setPickupDeadline(NOW.plus(Duration.ofMinutes(30)));
    }

    @Test
    void cancelReturnsUnitsToTheCounterTheyCameFrom() {
        when(orderRepository.findById(42L)).thenReturn(Optional.of(order));
        when(foodItemRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(item));

        OrderResponse response = service.cancel(7L, 42L);

        assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(item.getQuantityAvailable()).isEqualTo(3);
        assertThat(item.getPayItForwardQuantity()).isZero();
    }

    @Test
    void cancelOfFreeClaimReturnsUnitToPayItForwardPool() {
        order.setType(OrderType.PAY_IT_FORWARD);
        order.setQuantity(1);
        when(orderRepository.findById(42L)).thenReturn(Optional.of(order));
        when(foodItemRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(item));

        service.cancel(7L, 42L);

        assertThat(item.getPayItForwardQuantity()).isEqualTo(1);
        assertThat(item.getQuantityAvailable()).isEqualTo(1);
    }

    @Test
    void cancelOfSomeoneElsesOrderLooksLikeMissingOrder() {
        when(orderRepository.findById(42L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.cancel(99L, 42L))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.RESERVED);
    }

    @Test
    void cancelOfCollectedOrderIsRejected() {
        order.setStatus(OrderStatus.PICKED_UP);
        when(orderRepository.findById(42L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.cancel(7L, 42L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("PICKED_UP");
    }

    @Test
    void pickupWithValidCodeMarksOrderCollected() {
        when(orderRepository.findByFoodItemStoreIdAndStatusAndPickupCode(1L, OrderStatus.RESERVED, "123456"))
                .thenReturn(Optional.of(order));

        OrderResponse response = service.confirmPickup(5L, 1L, "123456");

        assertThat(response.status()).isEqualTo(OrderStatus.PICKED_UP);
    }

    @Test
    void pickupWithUnknownCodeIsRejected() {
        when(orderRepository.findByFoodItemStoreIdAndStatusAndPickupCode(1L, OrderStatus.RESERVED, "000000"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirmPickup(5L, 1L, "000000"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("No open reservation");
    }

    @Test
    void pickupAfterDeadlineIsRejectedEvenBeforeTheExpiryJobRan() {
        order.setPickupDeadline(NOW.minusSeconds(1));
        when(orderRepository.findByFoodItemStoreIdAndStatusAndPickupCode(1L, OrderStatus.RESERVED, "123456"))
                .thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.confirmPickup(5L, 1L, "123456"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("deadline");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.RESERVED);
    }

    @Test
    void pickupAtAStoreTheCallerDoesNotOwnIsRejected() {
        when(storeService.requireOwnedStore(5L, 1L))
                .thenThrow(new BusinessRuleException(org.springframework.http.HttpStatus.FORBIDDEN, "You do not manage this store"));

        assertThatThrownBy(() -> service.confirmPickup(5L, 1L, "123456"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("do not manage");
    }
}

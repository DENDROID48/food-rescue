package com.foodrescue.service;

import com.foodrescue.domain.FoodItem;
import com.foodrescue.domain.Store;
import com.foodrescue.domain.User;
import com.foodrescue.domain.UserRole;
import com.foodrescue.dto.FoodItemRequest;
import com.foodrescue.dto.StoreRequest;
import com.foodrescue.exception.BusinessRuleException;
import com.foodrescue.exception.ResourceNotFoundException;
import com.foodrescue.repository.FoodItemRepository;
import com.foodrescue.repository.OrderRepository;
import com.foodrescue.repository.StoreRepository;
import com.foodrescue.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Pure unit tests for the store owner rules, with mocked repositories. */
@ExtendWith(MockitoExtension.class)
class StoreServiceTest {

    @Mock StoreRepository storeRepository;
    @Mock FoodItemRepository foodItemRepository;
    @Mock OrderRepository orderRepository;
    @Mock UserRepository userRepository;
    @Mock GeocodingService geocodingService;

    StoreService service;
    User owner;
    Store store;
    FoodItem item;

    @BeforeEach
    void setUp() {
        service = new StoreService(storeRepository, foodItemRepository, orderRepository, userRepository, geocodingService);

        owner = new User();
        owner.setId(1L);
        owner.setRole(UserRole.STORE_OWNER);

        store = new Store();
        store.setId(10L);
        store.setOwner(owner);

        item = new FoodItem();
        item.setId(100L);
        item.setStore(store);
        item.setQuantityAvailable(4);
        item.setPayItForwardQuantity(1);
    }

    private static FoodItemRequest request(String original, String discounted, int quantity, int payItForward) {
        return new FoodItemRequest("Bread", null, new BigDecimal(original), new BigDecimal(discounted),
                quantity, payItForward, Instant.parse("2030-01-01T00:00:00Z"));
    }

    @Test
    void consumersCannotRegisterStores() {
        owner.setRole(UserRole.CONSUMER);
        when(userRepository.findById(1L)).thenReturn(Optional.of(owner));

        assertThatThrownBy(() -> service.createStore(1L, new StoreRequest("Shop", "Street 1", 45.0, 21.0)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("store owner");
        verify(storeRepository, never()).save(any());
    }

    @Test
    void onlyTheOwnerCanManageAStore() {
        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));

        assertThatThrownBy(() -> service.listItems(2L, 10L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("do not manage");
    }

    @Test
    void createItemRejectsPriceThatIsNotADiscount() {
        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));

        assertThatThrownBy(() -> service.createItem(1L, 10L, request("5.00", "5.00", 3, 0)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("lower than originalPrice");
    }

    @Test
    void createItemRejectsZeroUnits() {
        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));

        assertThatThrownBy(() -> service.createItem(1L, 10L, request("5.00", "2.00", 0, 0)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("at least one unit");
    }

    @Test
    void updateOfItemFromAnotherStoreLooksLikeMissingItem() {
        Store otherStore = new Store();
        otherStore.setId(11L);
        item.setStore(otherStore);
        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(foodItemRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> service.updateItem(1L, 10L, 100L, request("5.00", "2.00", 3, 0)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void deletingAnOrderedItemKeepsItButRemovesTheStock() {
        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(foodItemRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(item));
        when(orderRepository.existsByFoodItemId(100L)).thenReturn(true);

        service.deleteItem(1L, 10L, 100L);

        assertThat(item.getQuantityAvailable()).isZero();
        assertThat(item.getPayItForwardQuantity()).isZero();
        verify(foodItemRepository, never()).delete(any());
    }

    @Test
    void deletingANeverOrderedItemRemovesIt() {
        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(foodItemRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(item));
        when(orderRepository.existsByFoodItemId(100L)).thenReturn(false);

        service.deleteItem(1L, 10L, 100L);

        verify(foodItemRepository).delete(item);
    }
}

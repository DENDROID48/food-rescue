package com.foodrescue.service;

import com.foodrescue.domain.FoodItem;
import com.foodrescue.domain.Store;
import com.foodrescue.domain.User;
import com.foodrescue.domain.UserRole;
import com.foodrescue.dto.FoodItemRequest;
import com.foodrescue.dto.FoodItemResponse;
import com.foodrescue.dto.PlaceResponse;
import com.foodrescue.dto.StoreRequest;
import com.foodrescue.dto.StoreResponse;
import com.foodrescue.exception.BusinessRuleException;
import com.foodrescue.exception.ResourceNotFoundException;
import com.foodrescue.repository.FoodItemRepository;
import com.foodrescue.repository.OrderRepository;
import com.foodrescue.repository.StoreRepository;
import com.foodrescue.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;

/**
 * Everything a store owner does with their stores and the food they publish.
 *
 * <p>Every method receives the id of the calling user and checks that this user owns the
 * store. When Spring Security replaces the {@code X-User-Id} header, only the place the id
 * comes from changes, not these checks.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StoreService {

    private final StoreRepository storeRepository;
    private final FoodItemRepository foodItemRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final GeocodingService geocodingService;

    // ------------------------------------------------------------------------------------
    // Stores
    // ------------------------------------------------------------------------------------

    @Transactional
    public StoreResponse createStore(Long ownerId, StoreRequest request) {
        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("User", ownerId));
        if (owner.getRole() != UserRole.STORE_OWNER) {
            throw new BusinessRuleException(HttpStatus.FORBIDDEN, "Only store owner accounts can register stores");
        }

        Store store = new Store();
        store.setOwner(owner);
        applyStore(store, request);
        store.setTimeZone(request.timeZone() == null ? Store.DEFAULT_TIME_ZONE : checkZone(request.timeZone()));

        Store saved = storeRepository.save(store);
        log.info("User {} registered store {}", ownerId, saved.getId());
        return StoreResponse.from(saved);
    }

    /** Changes name and address. Without new coordinates the changed address is looked up again. */
    @Transactional
    public StoreResponse updateStore(Long ownerId, Long storeId, StoreRequest request) {
        Store store = requireOwnedStore(ownerId, storeId);
        boolean addressChanged = !store.getAddress().equals(request.address().trim());
        if (addressChanged || request.latitude() != null) {
            applyStore(store, request);
        } else {
            store.setName(request.name().trim());
        }
        if (request.timeZone() != null) {
            store.setTimeZone(checkZone(request.timeZone()));
        }
        log.info("User {} updated store {}", ownerId, storeId);
        return StoreResponse.from(store);
    }

    /**
     * Copies name and address, and sets the position: the coordinates sent by the client when
     * the owner placed the pin on the map, otherwise the result of looking the address up.
     */
    private void applyStore(Store store, StoreRequest request) {
        if ((request.latitude() == null) != (request.longitude() == null)) {
            throw new BusinessRuleException(HttpStatus.BAD_REQUEST, "Send both latitude and longitude, or neither");
        }
        String address = request.address().trim();
        if (request.latitude() != null) {
            store.setLatitude(request.latitude());
            store.setLongitude(request.longitude());
        } else {
            PlaceResponse place = geocodingService.locateAddress(address);
            store.setLatitude(place.latitude());
            store.setLongitude(place.longitude());
        }
        store.setName(request.name().trim());
        store.setAddress(address);
    }

    /** Accepts IANA names such as "Europe/Bucharest"; fixed offsets like "+02:00" would ignore summer time. */
    private static String checkZone(String timeZone) {
        try {
            if (!timeZone.contains("/")) {
                throw new DateTimeException(timeZone);
            }
            return ZoneId.of(timeZone.trim()).getId();
        } catch (DateTimeException e) {
            throw new BusinessRuleException(HttpStatus.BAD_REQUEST, "Unknown time zone: " + timeZone);
        }
    }

    @Transactional(readOnly = true)
    public List<StoreResponse> listOwnStores(Long ownerId) {
        return storeRepository.findByOwnerId(ownerId).stream()
                .map(StoreResponse::from)
                .toList();
    }

    /**
     * Loads a store and checks that the caller owns it. 404 when the store does not exist,
     * 403 when it belongs to someone else. Also used by {@link OrderService} for pickups.
     */
    @Transactional(readOnly = true)
    public Store requireOwnedStore(Long ownerId, Long storeId) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new ResourceNotFoundException("Store", storeId));
        if (!store.getOwner().getId().equals(ownerId)) {
            throw new BusinessRuleException(HttpStatus.FORBIDDEN, "You do not manage this store");
        }
        return store;
    }

    // ------------------------------------------------------------------------------------
    // Food items
    // ------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<FoodItemResponse> listItems(Long ownerId, Long storeId) {
        requireOwnedStore(ownerId, storeId);
        return foodItemRepository.findByStoreIdOrderByExpiresAtDesc(storeId).stream()
                .map(FoodItemResponse::from)
                .toList();
    }

    @Transactional
    public FoodItemResponse createItem(Long ownerId, Long storeId, FoodItemRequest request) {
        Store store = requireOwnedStore(ownerId, storeId);
        validate(request);
        if (request.quantityAvailable() + request.payItForwardQuantity() == 0) {
            throw new BusinessRuleException(HttpStatus.BAD_REQUEST,
                    "Offer at least one unit for sale or as Pay-It-Forward");
        }

        FoodItem item = new FoodItem();
        item.setStore(store);
        apply(item, request);

        FoodItem saved = foodItemRepository.save(item);
        log.info("Store {} published item {}", storeId, saved.getId());
        return FoodItemResponse.from(saved);
    }

    /**
     * Replaces every field of an item. The row is locked like during a reservation, so an
     * owner changing the stock and a customer reserving at the same moment cannot overwrite
     * each other's change.
     */
    @Transactional
    public FoodItemResponse updateItem(Long ownerId, Long storeId, Long itemId, FoodItemRequest request) {
        requireOwnedStore(ownerId, storeId);
        validate(request);
        FoodItem item = loadItemOfStoreForUpdate(storeId, itemId);
        apply(item, request);
        log.info("Store {} updated item {}", storeId, itemId);
        return FoodItemResponse.from(item);
    }

    /**
     * Takes an item off the market. An item nobody ordered is deleted. An item with orders is
     * kept, because those orders point to it, and its stock is set to zero: it disappears from
     * search while customers can still collect what they already reserved.
     */
    @Transactional
    public void deleteItem(Long ownerId, Long storeId, Long itemId) {
        requireOwnedStore(ownerId, storeId);
        FoodItem item = loadItemOfStoreForUpdate(storeId, itemId);

        if (orderRepository.existsByFoodItemId(itemId)) {
            item.setQuantityAvailable(0);
            item.setPayItForwardQuantity(0);
            log.info("Store {} withdrew item {} (kept for existing orders)", storeId, itemId);
        } else {
            foodItemRepository.delete(item);
            log.info("Store {} deleted item {}", storeId, itemId);
        }
    }

    /** Loads and locks an item, checking it belongs to the store. Also used by FoodItemPhotoService. */
    FoodItem loadItemOfStoreForUpdate(Long storeId, Long itemId) {
        FoodItem item = foodItemRepository.findByIdForUpdate(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Food item", itemId));
        // An id from another store is reported as missing, so ids of other stores are not revealed.
        if (!item.getStore().getId().equals(storeId)) {
            throw new ResourceNotFoundException("Food item", itemId);
        }
        return item;
    }

    /**
     * The entity checks prices again before saving, but checking here gives the client a
     * clear 400 instead of a generic 500.
     */
    private void validate(FoodItemRequest request) {
        if (request.discountedPrice().compareTo(request.originalPrice()) >= 0) {
            throw new BusinessRuleException(HttpStatus.BAD_REQUEST,
                    "discountedPrice must be lower than originalPrice");
        }
        if (request.pickupStartsAt() != null && !request.pickupStartsAt().isBefore(request.expiresAt())) {
            throw new BusinessRuleException(HttpStatus.BAD_REQUEST, "The pickup window must start before it ends");
        }
    }

    private void apply(FoodItem item, FoodItemRequest request) {
        item.setName(request.name().trim());
        item.setDescription(request.description());
        item.setOriginalPrice(request.originalPrice());
        item.setDiscountedPrice(request.discountedPrice());
        item.setShelf(request.quantityAvailable(), request.payItForwardQuantity());
        item.setUnitWeightGrams(request.unitWeightGrams());
        item.setExpiresAt(request.expiresAt());
        item.setPickupStartsAt(request.pickupStartsAt());
        if (request.kind() != null) {
            item.setKind(request.kind());
        }
        if (request.tags() != null) {
            item.setTags(request.tags().isEmpty() ? java.util.EnumSet.noneOf(com.foodrescue.domain.FoodTag.class)
                    : java.util.EnumSet.copyOf(request.tags()));
        }
    }
}

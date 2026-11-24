package com.foodrescue.service;

import com.foodrescue.domain.BundleComponent;
import com.foodrescue.domain.FoodItem;
import com.foodrescue.domain.FoodItemPhoto;
import com.foodrescue.domain.FoodTag;
import com.foodrescue.domain.OfferKind;
import com.foodrescue.domain.OfferStatus;
import com.foodrescue.domain.ProductTemplate;
import com.foodrescue.domain.Store;
import com.foodrescue.dto.BundleDropRequest;
import com.foodrescue.dto.DropResponse;
import com.foodrescue.dto.FoodItemResponse;
import com.foodrescue.dto.LaunchDropRequest;
import com.foodrescue.exception.BusinessRuleException;
import com.foodrescue.repository.BundleComponentRepository;
import com.foodrescue.repository.FoodItemPhotoRepository;
import com.foodrescue.storage.PhotoFolder;
import com.foodrescue.storage.PhotoStorage;
import com.foodrescue.repository.FoodItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Rescue Drops: surplus offered right now, for a short time, in a few taps.
 *
 * <p>A drop is an ordinary offer (so reservations, pickup codes, Pay-It-Forward and the stock
 * locking all work unchanged) whose pickup window starts at launch and lasts 10 to 240 minutes.
 * It ends by itself: customers stop seeing it as soon as the stock reaches zero or the window
 * passes, because search and reservations already check both.</p>
 *
 * <p>The owner always states the quantity, so no stock is ever published without being
 * confirmed. A Smart Bundle moves units the owner had already confirmed for today from other
 * offers into surprise bags.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RescueDropService {

    private final StoreService storeService;
    private final ProductTemplateService templateService;
    private final DailyOfferService dailyOfferService;
    private final AlertService alertService;
    private final FoodItemRepository foodItemRepository;
    private final FoodItemPhotoRepository photoRepository;
    private final PhotoStorage photoStorage;
    private final BundleComponentRepository bundleComponentRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public int audience(Long ownerId, Long storeId) {
        return alertService.audience(storeService.requireOwnedStore(ownerId, storeId));
    }

    /** Launches a drop of a saved product, starting now. */
    @Transactional
    public DropResponse launch(Long ownerId, Long storeId, LaunchDropRequest request) {
        Store store = requireActive(storeService.requireOwnedStore(ownerId, storeId));
        ProductTemplate template = templateService.loadOwnedForUpdate(storeId, request.templateId());
        String name = "“" + template.getName() + "”";
        if (template.isArchived()) {
            throw new BusinessRuleException(HttpStatus.CONFLICT, name + " was archived");
        }
        int free = request.payItForwardQuantity() == null ? 0 : request.payItForwardQuantity();
        if (request.quantityAvailable() + free == 0) {
            throw new BusinessRuleException(HttpStatus.BAD_REQUEST, "Enter how many units are on the shelf now");
        }
        Instant now = clock.instant();
        if (foodItemRepository.existsByTemplateIdAndRescueDropTrueAndExpiresAtAfter(template.getId(), now)) {
            throw new BusinessRuleException(HttpStatus.CONFLICT,
                    name + " already has a Rescue Drop running. Change that drop instead.");
        }

        FoodItem drop = newDrop(store, now, request.durationMinutes());
        drop.setTemplate(template);
        drop.setName(template.getName());
        drop.setDescription(template.getDescription());
        drop.setKind(template.getKind());
        drop.setTags(DailyOfferService.withNone(template.getTags()));
        drop.setUnitWeightGrams(template.getUnitWeightGrams());
        drop.setOriginalPrice(template.getOriginalPrice());
        drop.setDiscountedPrice(DailyOfferService.approvedPrice(template, request.discountedPrice(), name));
        drop.setQuantityAvailable(request.quantityAvailable());
        drop.setPayItForwardQuantity(free);
        drop = foodItemRepository.save(drop);
        dailyOfferService.copyPhoto(template, drop);
        return finish(drop, request.alertCustomers(), now);
    }

    /**
     * Turns leftover units of today's offers into surprise bags launched as a drop. Every source
     * offer is locked (in id order, so two bundles can never deadlock), checked and reduced in
     * the same transaction, so a customer reserving at the same moment can never get a unit
     * that went into a bag, and the other way round.
     */
    @Transactional
    public DropResponse bundle(Long ownerId, Long storeId, BundleDropRequest request) {
        Store store = requireActive(storeService.requireOwnedStore(ownerId, storeId));
        Instant now = clock.instant();

        Set<Long> ids = new HashSet<>();
        for (BundleDropRequest.Component c : request.components()) {
            if (!ids.add(c.offerId())) {
                throw new BusinessRuleException(HttpStatus.BAD_REQUEST, "Each offer can appear only once");
            }
        }
        List<BundleDropRequest.Component> sorted = request.components().stream()
                .sorted(Comparator.comparing(BundleDropRequest.Component::offerId)).toList();

        List<FoodItem> sources = new ArrayList<>();
        BigDecimal value = BigDecimal.ZERO;
        int units = 0;
        for (BundleDropRequest.Component c : sorted) {
            FoodItem source = storeService.loadItemOfStoreForUpdate(storeId, c.offerId());
            if (source.isExpiredAt(now)) {
                throw new BusinessRuleException(HttpStatus.CONFLICT, "“" + source.getName() + "” has already ended");
            }
            if (source.getStatus() == OfferStatus.DRAFT) {
                throw new BusinessRuleException(HttpStatus.CONFLICT,
                        "“" + source.getName() + "” is a draft; its stock was never confirmed for sale");
            }
            if (c.units() > source.getQuantityAvailable()) {
                throw new BusinessRuleException(HttpStatus.CONFLICT, "Only " + source.getQuantityAvailable()
                        + " unit(s) of “" + source.getName() + "” are left");
            }
            source.setQuantityAvailable(source.getQuantityAvailable() - c.units());
            sources.add(source);
            value = value.add(source.getOriginalPrice().multiply(BigDecimal.valueOf(c.units())));
            units += c.units();
        }
        if (request.bags() > units) {
            throw new BusinessRuleException(HttpStatus.BAD_REQUEST,
                    "Every bag needs at least one unit: " + units + " units for " + request.bags() + " bags");
        }
        BigDecimal bagValue = value.divide(BigDecimal.valueOf(request.bags()), 2, RoundingMode.DOWN);
        if (request.price().compareTo(bagValue) >= 0) {
            throw new BusinessRuleException(HttpStatus.BAD_REQUEST,
                    "The bag price must be below the value of its contents (" + bagValue.toPlainString() + " lei)");
        }

        FoodItem drop = newDrop(store, now, request.durationMinutes());
        String name = request.name() == null || request.name().isBlank() ? "Rescue surprise bag" : request.name().trim();
        drop.setName(name);
        drop.setDescription(contents(sources));
        drop.setKind(OfferKind.SURPRISE_BAG);
        drop.setTags(bundleTags(sources));
        drop.setUnitWeightGrams(bagWeight(sources, sorted, request.bags()));
        drop.setOriginalPrice(bagValue);
        drop.setDiscountedPrice(request.price().setScale(2, RoundingMode.HALF_UP));
        drop.setQuantityAvailable(request.bags());
        drop.setPayItForwardQuantity(0);
        drop = foodItemRepository.save(drop);

        for (int i = 0; i < sorted.size(); i++) {
            BundleComponent component = new BundleComponent();
            component.setBundle(drop);
            component.setSource(sources.get(i));
            component.setUnits(sorted.get(i).units());
            bundleComponentRepository.save(component);
        }
        copyFirstPhoto(sources, drop);
        log.info("Store {} bundled {} unit(s) from {} offer(s) into {} bag(s)", storeId, units, sources.size(), request.bags());
        return finish(drop, request.alertCustomers(), now);
    }

    /**
     * Food types of a bag: every type found in its contents, but "vegetarian" or "vegan" only when
     * every part of the bag carries that statement from the shop.
     */
    static Set<FoodTag> bundleTags(List<FoodItem> sources) {
        java.util.EnumSet<FoodTag> tags = java.util.EnumSet.noneOf(FoodTag.class);
        sources.forEach(s -> s.getTags().stream().filter(t -> !t.isDietary()).forEach(tags::add));
        for (FoodTag dietary : List.of(FoodTag.VEGETARIAN, FoodTag.VEGAN)) {
            if (sources.stream().allMatch(s -> s.getTags().contains(dietary))) {
                tags.add(dietary);
            }
        }
        return tags;
    }

    /** Weight of one bag, when every part of it has a weight; otherwise unknown. */
    static Integer bagWeight(List<FoodItem> sources, List<BundleDropRequest.Component> parts, int bags) {
        long grams = 0;
        for (int i = 0; i < sources.size(); i++) {
            if (sources.get(i).getUnitWeightGrams() == null) return null;
            grams += (long) sources.get(i).getUnitWeightGrams() * parts.get(i).units();
        }
        return (int) Math.max(1, Math.round((double) grams / bags));
    }

    /** States only what the bag is made from; no claims about freshness or allergens. */
    private static String contents(List<FoodItem> sources) {
        Set<String> names = new LinkedHashSet<>();
        sources.forEach(s -> names.add(s.getName()));
        String text = "A mix of today's " + String.join(", ", names) + ". Contents vary.";
        return text.length() <= 500 ? text : text.substring(0, 499) + "…";
    }

    private void copyFirstPhoto(List<FoodItem> sources, FoodItem drop) {
        sources.stream().filter(FoodItem::isHasPhoto).findFirst()
                .flatMap(s -> photoRepository.findById(s.getId()))
                .filter(source -> photoStorage.copy(PhotoFolder.FOOD_ITEMS, source.getFoodItemId(), PhotoFolder.FOOD_ITEMS, drop.getId()))
                .ifPresent(source -> {
                    FoodItemPhoto photo = new FoodItemPhoto();
                    photo.setFoodItemId(drop.getId());
                    photo.setContentType(source.getContentType());
                    photoRepository.save(photo);
                    drop.setHasPhoto(true);
                });
    }

    private FoodItem newDrop(Store store, Instant now, int minutes) {
        FoodItem drop = new FoodItem();
        drop.setStore(store);
        drop.setRescueDrop(true);
        drop.setStatus(OfferStatus.ACTIVE);
        drop.setPickupStartsAt(now);
        drop.setExpiresAt(now.plus(Duration.ofMinutes(minutes)));
        drop.setCreatedAt(now);
        return drop;
    }

    private DropResponse finish(FoodItem drop, boolean notify, Instant now) {
        foodItemRepository.flush();
        int notified = notify ? alertService.alertAbout(drop) : 0;
        return new DropResponse(FoodItemResponse.from(drop, now), notified);
    }

    private static Store requireActive(Store store) {
        if (!store.isActive()) {
            throw new BusinessRuleException(HttpStatus.CONFLICT,
                    "Your store is not active, so customers cannot see drops right now");
        }
        return store;
    }
}

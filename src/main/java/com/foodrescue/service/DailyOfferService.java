package com.foodrescue.service;

import com.foodrescue.domain.FoodItem;
import com.foodrescue.domain.FoodItemPhoto;
import com.foodrescue.domain.OfferStatus;
import com.foodrescue.domain.OrderStatus;
import com.foodrescue.domain.OrderType;
import com.foodrescue.domain.ProductTemplate;
import com.foodrescue.domain.Store;
import com.foodrescue.dto.FoodItemResponse;
import com.foodrescue.dto.OfferUpdateRequest;
import com.foodrescue.dto.PublishOffersRequest;
import com.foodrescue.dto.ReminderRequest;
import com.foodrescue.dto.TemplateResponse;
import com.foodrescue.dto.TodayResponse;
import com.foodrescue.exception.BusinessRuleException;
import com.foodrescue.repository.FoodItemPhotoRepository;
import com.foodrescue.repository.FoodItemRepository;
import com.foodrescue.repository.OrderRepository;
import com.foodrescue.repository.ProductTemplatePhotoRepository;
import com.foodrescue.storage.PhotoFolder;
import com.foodrescue.storage.PhotoStorage;
import com.foodrescue.repository.ProductTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The store's daily routine: confirm today's quantities for reusable products, publish them in
 * one step, adjust or pause them during the day, and see the day's figures.
 *
 * <p><b>Days and time zones.</b> "Today" is the calendar day in the store's own time zone, and
 * pickup times such as 18:00 are converted to exact instants in that zone. The server's zone and
 * the owner's phone never matter.</p>
 *
 * <p><b>No stock carried over.</b> Templates have no quantity and every publish request states
 * the quantities explicitly, so yesterday's numbers are never published again by themselves.
 * An offer ends at the end of its pickup window, after which customers cannot see or reserve it.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DailyOfferService {

    private final StoreService storeService;
    private final ProductTemplateService templateService;
    private final ProductTemplateRepository templateRepository;
    private final ProductTemplatePhotoRepository templatePhotoRepository;
    private final FoodItemRepository foodItemRepository;
    private final FoodItemPhotoRepository foodItemPhotoRepository;
    private final PhotoStorage photoStorage;
    private final OrderRepository orderRepository;
    private final AutopilotService autopilotService;
    private final AlertService alertService;
    private final Clock clock;

    // ------------------------------------------------------------------------------------
    // Dashboard
    // ------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public TodayResponse today(Long ownerId, Long storeId) {
        Store store = storeService.requireOwnedStore(ownerId, storeId);
        ZoneId zone = store.zone();
        Instant now = clock.instant();
        LocalDate date = LocalDate.ofInstant(now, zone);
        Instant dayStart = date.atStartOfDay(zone).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant();

        List<FoodItem> items = foodItemRepository.findByStoreIdAndExpiresAtAfterOrderByExpiresAtAsc(storeId, dayStart);
        List<FoodItemResponse> offers = items.stream().map(item -> FoodItemResponse.from(item, now)).toList();
        AutopilotService.Live live = autopilotService.alerts(items, now);
        List<TemplateResponse> templates = templateRepository.findByStoreIdAndArchivedFalseOrderByNameAsc(storeId)
                .stream().map(TemplateResponse::from).toList();

        FoodItemRepository.StockTotals stock = foodItemRepository.sumLiveStock(storeId, now);
        TodayResponse.Insights insights = new TodayResponse.Insights(
                stock.getForSale(),
                stock.getFree(),
                orderRepository.sumUnitsByStatus(storeId, OrderStatus.RESERVED),
                orderRepository.sumCollectedUnits(storeId, dayStart, dayEnd),
                orderRepository.sumCollectedUnitsOfType(storeId, OrderType.PAY_IT_FORWARD, dayStart, dayEnd),
                stock.getOffers());

        return new TodayResponse(date, store.getTimeZone(), now, insights, reminder(store, now, date, dayStart), offers, templates,
                live.alerts(), live.bundle());
    }

    /**
     * The in-app reminder is due when the store turned it on, its local time has passed, nothing
     * was published or drafted today and the owner did not dismiss it today. It is calculated on
     * request, so it needs no background job; a scheduled e-mail or push channel could reuse
     * exactly this rule later.
     */
    private TodayResponse.Reminder reminder(Store store, Instant now, LocalDate date, Instant dayStart) {
        LocalTime time = store.getReminderTime();
        if (time == null) {
            return new TodayResponse.Reminder(false, null, false);
        }
        boolean due = !LocalTime.ofInstant(now, store.zone()).isBefore(time)
                && !date.equals(store.getReminderDismissedOn())
                && foodItemRepository.countByStoreIdAndCreatedAtGreaterThanEqual(store.getId(), dayStart) == 0;
        return new TodayResponse.Reminder(true, time, due);
    }

    // ------------------------------------------------------------------------------------
    // Publishing
    // ------------------------------------------------------------------------------------

    /**
     * Creates today's offers from templates, all or nothing. Each template row is locked while
     * its offer is created, so the same product cannot be published twice for the same day even
     * when the button is pressed twice or from two phones.
     */
    @Transactional
    public List<FoodItemResponse> publish(Long ownerId, Long storeId, PublishOffersRequest request) {
        Store store = storeService.requireOwnedStore(ownerId, storeId);
        ZoneId zone = store.zone();
        Instant now = clock.instant();
        LocalDate date = LocalDate.ofInstant(now, zone);

        Set<Long> seen = new HashSet<>();
        List<FoodItemResponse> created = new ArrayList<>();
        List<FoodItem> items = new ArrayList<>();
        for (PublishOffersRequest.Line line : request.offers()) {
            if (!seen.add(line.templateId())) {
                throw new BusinessRuleException(HttpStatus.BAD_REQUEST, "Each product can appear only once");
            }
            ProductTemplate template = templateService.loadOwnedForUpdate(storeId, line.templateId());
            String name = "“" + template.getName() + "”";
            if (template.isArchived()) {
                throw new BusinessRuleException(HttpStatus.CONFLICT, name + " was archived");
            }
            if (line.quantityAvailable() + line.payItForwardQuantity() == 0) {
                throw new BusinessRuleException(HttpStatus.BAD_REQUEST, "Enter today's quantity for " + name);
            }
            Window window = window(date, line.pickupStart(), line.pickupEnd(), zone, now, name);
            if (foodItemRepository.existsByTemplateIdAndRescueDropFalseAndExpiresAtAfter(template.getId(), now)) {
                throw new BusinessRuleException(HttpStatus.CONFLICT,
                        name + " is already on offer today. Change that offer instead.");
            }

            FoodItem item = new FoodItem();
            item.setStore(store);
            item.setTemplate(template);
            item.setName(template.getName());
            item.setDescription(template.getDescription());
            item.setKind(template.getKind());
            item.setTags(java.util.EnumSet.copyOf(withNone(template.getTags())));
            item.setUnitWeightGrams(template.getUnitWeightGrams());
            item.setOriginalPrice(template.getOriginalPrice());
            item.setDiscountedPrice(approvedPrice(template, line.discountedPrice(), name));
            item.setQuantityAvailable(line.quantityAvailable());
            item.setPayItForwardQuantity(line.payItForwardQuantity());
            item.setPickupStartsAt(window.start());
            item.setExpiresAt(window.end());
            item.setStatus(request.publish() ? OfferStatus.ACTIVE : OfferStatus.DRAFT);
            item.setCreatedAt(now);
            item = foodItemRepository.save(item);
            copyPhoto(template, item);
            items.add(item);
            created.add(FoodItemResponse.from(item, now));
        }
        log.info("Store {} {} {} offer(s)", storeId, request.publish() ? "published" : "drafted", created.size());
        if (request.publish()) {
            alertService.alertFavouritesAbout(items);
        }
        return created;
    }

    /**
     * Today's price. The owner may approve a different price for today (for example an Autopilot
     * suggestion) in the publish request itself; without one, the product's usual price is used.
     * The product itself is not changed.
     */
    static java.math.BigDecimal approvedPrice(ProductTemplate template, java.math.BigDecimal approved, String name) {
        if (approved == null) {
            return template.getDiscountedPrice();
        }
        if (approved.signum() <= 0 || approved.compareTo(template.getOriginalPrice()) >= 0) {
            throw new BusinessRuleException(HttpStatus.BAD_REQUEST,
                    "Today's price for " + name + " must be above 0 and below the original price");
        }
        return approved.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    static java.util.Set<com.foodrescue.domain.FoodTag> withNone(java.util.Set<com.foodrescue.domain.FoodTag> tags) {
        java.util.EnumSet<com.foodrescue.domain.FoodTag> copy = java.util.EnumSet.noneOf(com.foodrescue.domain.FoodTag.class);
        copy.addAll(tags);
        return copy;
    }

    /** The offer gets its own copy, so changing the template photo later does not change today's offer. */
    void copyPhoto(ProductTemplate template, FoodItem item) {
        if (!template.isHasPhoto()) {
            return;
        }
        templatePhotoRepository.findById(template.getId())
                .filter(source -> photoStorage.copy(PhotoFolder.TEMPLATES, template.getId(), PhotoFolder.FOOD_ITEMS, item.getId()))
                .ifPresent(source -> {
                    FoodItemPhoto photo = new FoodItemPhoto();
                    photo.setFoodItemId(item.getId());
                    photo.setContentType(source.getContentType());
                    foodItemPhotoRepository.save(photo);
                    item.setHasPhoto(true);
                });
    }

    // ------------------------------------------------------------------------------------
    // During the day
    // ------------------------------------------------------------------------------------

    /**
     * Sets the units still on the shelf and the pickup window. The offer row is locked like during
     * a reservation, so a customer reserving at the same moment cannot be overwritten. Units that
     * are already reserved belong to their orders and are not part of these numbers.
     */
    @Transactional
    public FoodItemResponse update(Long ownerId, Long storeId, Long itemId, OfferUpdateRequest request) {
        Store store = storeService.requireOwnedStore(ownerId, storeId);
        Instant now = clock.instant();
        FoodItem item = storeService.loadItemOfStoreForUpdate(storeId, itemId);
        requireNotEnded(item, now);
        Window window = window(LocalDate.ofInstant(now, store.zone()), request.pickupStart(), request.pickupEnd(),
                store.zone(), now, "this offer");
        item.setShelf(request.quantityAvailable(), request.payItForwardQuantity());
        item.setPickupStartsAt(window.start());
        item.setExpiresAt(window.end());
        log.info("Store {} adjusted offer {}", storeId, itemId);
        return FoodItemResponse.from(item, now);
    }

    /** Publishes a draft, pauses an offer, or resumes it. Paused offers keep their reservations. */
    @Transactional
    public FoodItemResponse setStatus(Long ownerId, Long storeId, Long itemId, OfferStatus status) {
        storeService.requireOwnedStore(ownerId, storeId);
        Instant now = clock.instant();
        FoodItem item = storeService.loadItemOfStoreForUpdate(storeId, itemId);
        requireNotEnded(item, now);
        if (status == OfferStatus.DRAFT) {
            throw new BusinessRuleException(HttpStatus.BAD_REQUEST, "An offer cannot go back to draft; pause it instead");
        }
        if (status == OfferStatus.ACTIVE && item.getQuantityAvailable() + item.getPayItForwardQuantity() == 0) {
            throw new BusinessRuleException(HttpStatus.CONFLICT, "Add at least one unit before publishing");
        }
        boolean firstPublish = item.getStatus() == OfferStatus.DRAFT && status == OfferStatus.ACTIVE;
        item.setStatus(status);
        log.info("Store {} set offer {} to {}", storeId, itemId, status);
        if (firstPublish) {
            // A draft going live is news; resuming a paused offer is not.
            alertService.alertFavouritesAbout(List.of(item));
        }
        return FoodItemResponse.from(item, now);
    }

    // ------------------------------------------------------------------------------------
    // Reminders
    // ------------------------------------------------------------------------------------

    @Transactional
    public TodayResponse.Reminder setReminder(Long ownerId, Long storeId, ReminderRequest request) {
        Store store = storeService.requireOwnedStore(ownerId, storeId);
        if (request.enabled() && request.time() == null) {
            throw new BusinessRuleException(HttpStatus.BAD_REQUEST, "Choose a time for the reminder");
        }
        store.setReminderTime(request.enabled() ? request.time() : null);
        Instant now = clock.instant();
        LocalDate date = LocalDate.ofInstant(now, store.zone());
        return reminder(store, now, date, date.atStartOfDay(store.zone()).toInstant());
    }

    /** Hides the reminder until tomorrow (in the store's zone). */
    @Transactional
    public void dismissReminder(Long ownerId, Long storeId) {
        Store store = storeService.requireOwnedStore(ownerId, storeId);
        store.setReminderDismissedOn(LocalDate.ofInstant(clock.instant(), store.zone()));
    }

    // ------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------

    record Window(Instant start, Instant end) {
    }

    /**
     * Turns "18:00 to 20:00 today" into exact instants in the store's zone. On the day clocks
     * change, a time that does not exist (02:30 when clocks jump to 03:00) moves forward by the
     * gap, as {@link ZonedDateTime#of} defines.
     */
    static Window window(LocalDate date, LocalTime start, LocalTime end, ZoneId zone, Instant now, String what) {
        if (!end.isAfter(start)) {
            throw new BusinessRuleException(HttpStatus.BAD_REQUEST,
                    "The pickup window for " + what + " must end after it starts, on the same day");
        }
        Instant from = ZonedDateTime.of(date, start, zone).toInstant();
        Instant to = ZonedDateTime.of(date, end, zone).toInstant();
        if (!to.isAfter(now)) {
            throw new BusinessRuleException(HttpStatus.BAD_REQUEST,
                    "The pickup window for " + what + " has already ended today");
        }
        return new Window(from, to);
    }

    private static void requireNotEnded(FoodItem item, Instant now) {
        if (item.isExpiredAt(now)) {
            throw new BusinessRuleException(HttpStatus.CONFLICT, "This offer has ended. Set up a new one for today.");
        }
    }
}

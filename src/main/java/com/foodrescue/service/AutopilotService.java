package com.foodrescue.service;

import com.foodrescue.domain.FoodItem;
import com.foodrescue.domain.OfferState;
import com.foodrescue.domain.OfferStatus;
import com.foodrescue.domain.OrderStatus;
import com.foodrescue.domain.OrderType;
import com.foodrescue.domain.ProductTemplate;
import com.foodrescue.domain.Store;
import com.foodrescue.dto.AutopilotPlan;
import com.foodrescue.dto.BundleSuggestion;
import com.foodrescue.dto.StoreAlert;
import com.foodrescue.repository.FoodItemRepository;
import com.foodrescue.repository.OrderRepository;
import com.foodrescue.repository.ProductTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Store Autopilot: suggestions computed from the store's own history, and alerts about today's
 * stock. It never publishes or changes anything by itself; every suggestion goes through the
 * owner's confirmation in the app.
 *
 * <p>The suggestions are deliberately simple statistics that an owner can follow ("sold out on 3
 * of the last 4 days"), not a black box. They need no external service.</p>
 */
@Service
@RequiredArgsConstructor
public class AutopilotService {

    /** How far back the Autopilot looks. Four weeks cover every weekday four times. */
    static final int HISTORY_DAYS = 28;
    /** Offers ending within this time with leftovers are proposed for a Smart Bundle. */
    static final Duration BUNDLE_HORIZON = Duration.ofMinutes(120);
    /** Units that make up one surprise bag in a bundle proposal. */
    static final int UNITS_PER_BAG = 3;

    private final StoreService storeService;
    private final ProductTemplateRepository templateRepository;
    private final FoodItemRepository foodItemRepository;
    private final OrderRepository orderRepository;
    private final Clock clock;

    // ------------------------------------------------------------------------------------
    // Daily plan
    // ------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public AutopilotPlan plan(Long ownerId, Long storeId) {
        Store store = storeService.requireOwnedStore(ownerId, storeId);
        ZoneId zone = store.zone();
        Instant now = clock.instant();
        LocalDate date = LocalDate.ofInstant(now, zone);

        List<FoodItem> past = foodItemRepository.findEndedTemplateOffers(storeId,
                date.minusDays(HISTORY_DAYS).atStartOfDay(zone).toInstant(), now);
        Map<Long, Units> units = units(past.stream().map(FoodItem::getId).toList());

        Map<Long, List<Day>> byTemplate = new HashMap<>();
        for (FoodItem item : past) {
            byTemplate.computeIfAbsent(item.getTemplate().getId(), id -> new ArrayList<>())
                    .add(Day.of(item, units.getOrDefault(item.getId(), Units.NONE), zone));
        }

        List<AutopilotPlan.Line> lines = templateRepository.findByStoreIdAndArchivedFalseOrderByNameAsc(storeId).stream()
                .map(t -> suggest(t, byTemplate.getOrDefault(t.getId(), List.of()), date.getDayOfWeek()))
                .toList();
        return new AutopilotPlan(date, date.getDayOfWeek(), lines);
    }

    /**
     * What happened to one past offer.
     *
     * @param sold    paid units collected
     * @param noShow  paid units reserved but never collected
     * @param left    paid units still on the shelf when the offer ended
     * @param free    free units offered (claimed or not)
     */
    record Day(LocalDate date, int sold, int noShow, int left, int free, LocalTime start, LocalTime end,
               BigDecimal price) {

        static Day of(FoodItem item, Units u, ZoneId zone) {
            LocalTime start = item.getPickupStartsAt() == null ? null : LocalTime.ofInstant(item.getPickupStartsAt(), zone);
            return new Day(LocalDate.ofInstant(item.getExpiresAt(), zone), u.paidSold + u.paidOpen, u.paidNoShow,
                    item.getQuantityAvailable(), item.getPayItForwardQuantity() + u.freeOrdered,
                    start, LocalTime.ofInstant(item.getExpiresAt(), zone), item.getDiscountedPrice());
        }

        int demand() {
            return sold + noShow;
        }

        int offered() {
            return demand() + left;
        }

        boolean soldOut() {
            return left == 0 && demand() > 0;
        }
    }

    /**
     * The suggestion for one product, from its past days (newest first). Days on the same weekday
     * as today count twice, because a Saturday is more like last Saturday than like a Tuesday.
     */
    static AutopilotPlan.Line suggest(ProductTemplate t, List<Day> history, DayOfWeek today) {
        List<String> reasons = new ArrayList<>();
        int n = history.size();
        if (n == 0) {
            reasons.add("No history yet. Enter today's quantity; suggestions start after a few days.");
            return new AutopilotPlan.Line(t.getId(), null, 0, t.getPickupStart(), t.getPickupEnd(), null, 0,
                    "NONE", reasons);
        }

        Day latest = history.get(0);
        Day window = history.stream().filter(d -> d.date().getDayOfWeek() == today && d.start() != null)
                .findFirst().orElse(latest);
        LocalTime start = window.start() != null ? window.start() : t.getPickupStart();
        LocalTime end = window.end();
        if (!end.isAfter(start)) {
            start = t.getPickupStart();
            end = t.getPickupEnd();
        } else if (window != latest || !start.equals(t.getPickupStart()) || !end.equals(t.getPickupEnd())) {
            reasons.add("Pickup times as on " + label(window.date()) + ".");
        }

        if (n == 1) {
            reasons.add("Same as last time (" + latest.offered() + "). More suggestions after a few days.");
            return new AutopilotPlan.Line(t.getId(), Math.max(latest.offered(), 1), latest.free(), start, end, null, 1,
                    "LOW", reasons);
        }

        double weights = 0, demand = 0, left = 0, offered = 0;
        int soldOutDays = 0, maxDemand = 0, reserved = 0, noShows = 0;
        for (Day d : history) {
            double w = d.date().getDayOfWeek() == today ? 2 : 1;
            weights += w;
            demand += w * d.demand();
            left += w * d.left();
            offered += w * d.offered();
            if (d.soldOut()) soldOutDays++;
            maxDemand = Math.max(maxDemand, d.demand());
            reserved += d.demand();
            noShows += d.noShow();
        }
        double avgDemand = demand / weights, avgLeft = left / weights, avgOffered = offered / weights;
        double soldOutShare = (double) soldOutDays / n;

        int quantity;
        if (soldOutShare >= 0.5) {
            // Demand was capped by the stock, so the real demand is higher than what was sold.
            quantity = Math.max((int) Math.ceil(avgDemand * 1.2), (int) Math.round(avgDemand) + 1);
            reasons.add("Sold out on " + soldOutDays + " of the last " + n + " days, so " + quantity
                    + " may meet demand.");
        } else {
            quantity = Math.max(1, (int) Math.ceil(avgDemand * 1.1));
            reasons.add("Usually about " + Math.round(avgDemand) + " reserved and " + Math.round(avgLeft)
                    + " left over.");
        }
        quantity = Math.min(quantity, 10_000);

        if (reserved >= 4 && noShows * 4 > reserved) {
            reasons.add(Math.round(100.0 * noShows / reserved) + "% of reservations were not collected.");
        }

        BigDecimal price = null;
        if (n >= 3 && avgOffered > 0 && avgLeft >= 0.4 * avgOffered && soldOutShare < 0.25) {
            price = lowerPrice(t.getDiscountedPrice(), t.getOriginalPrice());
            if (price != null) {
                reasons.add("About " + Math.round(100 * avgLeft / avgOffered) + "% usually goes unsold; a price of "
                        + price.toPlainString() + " lei may help. Applied only if you choose it.");
            }
        }

        String confidence = n >= 5 ? "HIGH" : n >= 3 ? "MEDIUM" : "LOW";
        return new AutopilotPlan.Line(t.getId(), quantity, latest.free(), start, end, price, n, confidence, reasons);
    }

    /**
     * 15 percent below the current price, rounded down to 0.10, but never below a fifth of the
     * original price. Null when no meaningful lower price exists.
     */
    static BigDecimal lowerPrice(BigDecimal current, BigDecimal original) {
        BigDecimal floor = original.multiply(new BigDecimal("0.20")).setScale(1, RoundingMode.CEILING);
        BigDecimal lower = current.multiply(new BigDecimal("0.85")).setScale(1, RoundingMode.FLOOR).max(floor)
                .setScale(2, RoundingMode.UNNECESSARY);
        return lower.compareTo(current) < 0 && lower.signum() > 0 ? lower : null;
    }

    private static String label(LocalDate date) {
        return date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + date.getDayOfMonth() + " "
                + date.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
    }

    // ------------------------------------------------------------------------------------
    // Alerts and Smart Bundle proposal for today's dashboard
    // ------------------------------------------------------------------------------------

    /** Today's offers with their order figures, for the alerts. */
    public record Live(List<StoreAlert> alerts, BundleSuggestion bundle) {
    }

    /**
     * Looks at today's offers and points out what needs attention. Called by the dashboard, so
     * the alerts are always as fresh as the data.
     */
    Live alerts(List<FoodItem> offers, Instant now) {
        List<StoreAlert> alerts = new ArrayList<>();
        Map<Long, Units> units = units(offers.stream().map(FoodItem::getId).toList());

        long drafts = offers.stream().filter(f -> f.state(now) == OfferState.DRAFT).count();
        if (drafts > 0) {
            alerts.add(new StoreAlert("DRAFTS_WAITING", "info",
                    drafts + (drafts == 1 ? " draft is" : " drafts are") + " not visible to customers yet.", null));
        }

        for (FoodItem f : offers) {
            OfferState state = f.state(now);
            Duration left = Duration.between(now, f.getExpiresAt());
            Units u = units.getOrDefault(f.getId(), Units.NONE);
            int ordered = u.paidSold + u.paidOpen;
            if (state == OfferState.ACTIVE && f.getQuantityAvailable() > 0 && left.toMinutes() >= 60
                    && ordered > 0 && f.getQuantityAvailable() * 5 <= f.getQuantityAvailable() + ordered) {
                alerts.add(new StoreAlert("LOW_STOCK", "warning", "“" + f.getName() + "” is selling fast: "
                        + f.getQuantityAvailable() + " of " + (f.getQuantityAvailable() + ordered)
                        + " left. Add more if you have them.", f.getId()));
            }
            if (state == OfferState.SOLD_OUT && left.toMinutes() >= 60 && !f.isRescueDrop()) {
                alerts.add(new StoreAlert("SOLD_OUT_EARLY", "info", "“" + f.getName() + "” sold out "
                        + hours(left) + " before pickup ends. The Autopilot will take this into account.", f.getId()));
            }
        }

        BundleSuggestion bundle = bundleSuggestion(offers, now);
        if (bundle != null) {
            int total = bundle.components().stream().mapToInt(BundleSuggestion.Component::units).sum();
            alerts.add(new StoreAlert("LEFTOVERS_ENDING", "warning", total + " unsold units end within "
                    + BUNDLE_HORIZON.toHours() + " hours. Bundle them into " + bundle.bags()
                    + (bundle.bags() == 1 ? " surprise bag" : " surprise bags") + " as a Rescue Drop?", null));
        }
        return new Live(alerts, bundle);
    }

    private static String hours(Duration d) {
        long minutes = d.toMinutes();
        return minutes < 120 ? minutes + " minutes" : (minutes / 60) + " hours";
    }

    /**
     * Proposes surprise bags made of the paid leftovers of live offers ending soon. A bag holds
     * about {@value #UNITS_PER_BAG} units and costs about 30 percent of what its contents cost at
     * full price, and less than buying the same items at their discounted prices.
     */
    static BundleSuggestion bundleSuggestion(Collection<FoodItem> offers, Instant now) {
        List<BundleSuggestion.Component> parts = new ArrayList<>();
        BigDecimal value = BigDecimal.ZERO, discounted = BigDecimal.ZERO;
        int total = 0;
        for (FoodItem f : offers) {
            if (f.state(now) != OfferState.ACTIVE || f.isRescueDrop() || f.getStatus() != OfferStatus.ACTIVE
                    || f.getQuantityAvailable() < 1
                    || Duration.between(now, f.getExpiresAt()).compareTo(BUNDLE_HORIZON) > 0) {
                continue;
            }
            int u = f.getQuantityAvailable();
            parts.add(new BundleSuggestion.Component(f.getId(), f.getName(), u, u, f.getOriginalPrice()));
            value = value.add(f.getOriginalPrice().multiply(BigDecimal.valueOf(u)));
            discounted = discounted.add(f.getDiscountedPrice().multiply(BigDecimal.valueOf(u)));
            total += u;
        }
        if (total < 2) {
            return null;
        }
        int bags = Math.max(1, total / UNITS_PER_BAG);
        BigDecimal perBag = BigDecimal.valueOf(bags);
        BigDecimal bagValue = value.divide(perBag, 2, RoundingMode.DOWN);
        BigDecimal price = bundlePrice(bagValue, discounted.divide(perBag, 2, RoundingMode.DOWN));
        if (price == null) {
            return null;
        }
        return new BundleSuggestion(parts, bags, price, bagValue, 60);
    }

    /** About 30% of the full value, at most 80% of the discounted value, rounded down to 0.50. */
    static BigDecimal bundlePrice(BigDecimal bagValue, BigDecimal bagDiscountedValue) {
        BigDecimal target = bagValue.multiply(new BigDecimal("0.30"))
                .min(bagDiscountedValue.multiply(new BigDecimal("0.80")));
        BigDecimal price = target.multiply(BigDecimal.TWO).setScale(0, RoundingMode.FLOOR)
                .divide(BigDecimal.TWO, 2, RoundingMode.UNNECESSARY);
        return price.signum() > 0 && price.compareTo(bagValue) < 0 ? price : null;
    }

    // ------------------------------------------------------------------------------------
    // Order figures per offer
    // ------------------------------------------------------------------------------------

    record Units(int paidSold, int paidNoShow, int paidOpen, int freeOrdered) {
        static final Units NONE = new Units(0, 0, 0, 0);
    }

    Map<Long, Units> units(List<Long> itemIds) {
        Map<Long, int[]> raw = new LinkedHashMap<>();
        if (itemIds.isEmpty()) {
            return Map.of();
        }
        for (Object[] row : orderRepository.unitsByItem(itemIds)) {
            int[] u = raw.computeIfAbsent((Long) row[0], id -> new int[4]);
            OrderType type = (OrderType) row[1];
            OrderStatus status = (OrderStatus) row[2];
            int qty = ((Number) row[3]).intValue();
            if (type == OrderType.PAY_IT_FORWARD) {
                if (status != OrderStatus.CANCELLED) u[3] += qty;
            } else if (status == OrderStatus.PICKED_UP) {
                u[0] += qty;
            } else if (status == OrderStatus.EXPIRED) {
                u[1] += qty;
            } else if (status == OrderStatus.RESERVED) {
                u[2] += qty;
            }
        }
        Map<Long, Units> result = new HashMap<>();
        raw.forEach((id, u) -> result.put(id, new Units(u[0], u[1], u[2], u[3])));
        return result;
    }
}

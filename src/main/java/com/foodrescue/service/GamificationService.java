package com.foodrescue.service;

import com.foodrescue.config.GamificationProperties;
import com.foodrescue.domain.Achievement;
import com.foodrescue.domain.FoodItem;
import com.foodrescue.domain.Order;
import com.foodrescue.domain.OrderStatus;
import com.foodrescue.domain.RadarProfile;
import com.foodrescue.domain.Store;
import com.foodrescue.dto.ImpactResponse;
import com.foodrescue.repository.OrderRepository;
import com.foodrescue.repository.RadarProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Rescue streaks, achievements, personal impact and the monthly community challenge.
 *
 * <p>Nothing is stored for this: everything is worked out from the customer's collected orders,
 * so the figures can never drift from the real history. Only collected orders count, never
 * reservations that were cancelled or not picked up.</p>
 *
 * <p>Streaks are counted in weeks (Monday to Sunday) rather than days: rescued food is not an
 * everyday purchase, and a daily streak would push people to buy food they do not need.</p>
 */
@Service
@RequiredArgsConstructor
public class GamificationService {

    private final OrderRepository orderRepository;
    private final RadarProfileRepository profileRepository;
    private final GamificationProperties properties;
    private final Clock clock;

    /** One collected order, reduced to what the calculations need. */
    record Rescue(Instant at, long storeId, int units, BigDecimal saved, int grams, boolean weightKnown, boolean drop) {

        static Rescue of(Order order) {
            FoodItem item = order.getFoodItem();
            BigDecimal full = item.getOriginalPrice().multiply(BigDecimal.valueOf(order.getQuantity()));
            BigDecimal saved = full.subtract(order.getTotalPrice()).max(BigDecimal.ZERO);
            Integer perUnit = item.getUnitWeightGrams();
            Instant at = order.getPickedUpAt() != null ? order.getPickedUpAt() : order.getCreatedAt();
            return new Rescue(at, item.getStore().getId(), order.getQuantity(), saved,
                    perUnit == null ? 0 : perUnit * order.getQuantity(), perUnit != null, item.isRescueDrop());
        }
    }

    @Transactional(readOnly = true)
    public ImpactResponse impact(Long userId) {
        ZoneId zone = profileRepository.findById(userId).map(RadarProfile::zone).orElse(ZoneId.of(Store.DEFAULT_TIME_ZONE));
        List<Rescue> rescues = orderRepository.findByUserIdAndStatusOrderByPickedUpAtAsc(userId, OrderStatus.PICKED_UP)
                .stream().map(Rescue::of).toList();

        LocalDate today = LocalDate.now(clock.withZone(zone));
        LocalDate monthStart = today.withDayOfMonth(1);
        Instant from = monthStart.atStartOfDay(zone).toInstant();
        Instant to = monthStart.plusMonths(1).atStartOfDay(zone).toInstant();
        long community = orderRepository.sumCollectedBetween(from, to);
        return compute(rescues, zone, today, community, properties.monthlyChallengeUnits());
    }

    /** All figures from a customer's collected orders; no database needed. */
    static ImpactResponse compute(List<Rescue> rescues, ZoneId zone, LocalDate today, long communityUnitsThisMonth,
                                  long monthlyTarget) {
        Totals totals = new Totals();
        Map<Achievement, LocalDate> unlocked = new EnumMap<>(Achievement.class);
        List<Rescue> inOrder = rescues.stream().sorted(java.util.Comparator.comparing(Rescue::at)).toList();
        for (Rescue r : inOrder) {
            LocalDate day = LocalDate.ofInstant(r.at(), zone);
            totals.add(r, day);
            // Replaying the history in order tells us the day each achievement was reached.
            for (Achievement a : Achievement.values()) {
                if (!unlocked.containsKey(a) && totals.value(a.metric()) >= a.target()) unlocked.put(a, day);
            }
        }

        List<ImpactResponse.AchievementView> achievements = new ArrayList<>();
        for (Achievement a : Achievement.values()) {
            long value = Math.min(totals.value(a.metric()), a.target());
            achievements.add(new ImpactResponse.AchievementView(a.name(), a.title(), a.description(),
                    unlocked.containsKey(a), unlocked.get(a), progressText(a, value),
                    (int) (value * 100 / a.target())));
        }

        LocalDate thisWeek = weekOf(today);
        boolean rescuedThisWeek = thisWeek.equals(totals.lastWeek);
        boolean alive = rescuedThisWeek || thisWeek.minusWeeks(1).equals(totals.lastWeek);
        ImpactResponse.Streak streak = new ImpactResponse.Streak(alive ? totals.runWeeks : 0, totals.bestWeeks, rescuedThisWeek);

        return new ImpactResponse(rescues.size(), totals.units, totals.saved.setScale(2, RoundingMode.HALF_UP),
                Math.round(totals.grams / 100.0) / 10.0, totals.unitsWithoutWeight, totals.shops.size(), streak,
                achievements, challenge(rescues, zone, today, communityUnitsThisMonth, monthlyTarget));
    }

    static ImpactResponse.Challenge challenge(List<Rescue> rescues, ZoneId zone, LocalDate today, long community, long target) {
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate endsOn = today.with(TemporalAdjusters.lastDayOfMonth());
        long yours = rescues.stream()
                .filter(r -> !LocalDate.ofInstant(r.at(), zone).isBefore(monthStart))
                .mapToLong(Rescue::units).sum();
        String month = today.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        return new ImpactResponse.Challenge("Rescue " + target + " items together in " + month, target, community, yours,
                (int) Math.min(100, community * 100 / target), endsOn, (int) ChronoUnit.DAYS.between(today, endsOn));
    }

    static LocalDate weekOf(LocalDate day) {
        return day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private static String progressText(Achievement a, long value) {
        return switch (a.metric()) {
            case GRAMS -> String.format(Locale.ROOT, "%.1f of %d kg", value / 1000.0, a.target() / 1000);
            case LEI_SAVED -> value + " of " + a.target() + " lei";
            case BEST_WEEK_STREAK -> value + " of " + a.target() + " weeks";
            case SHOPS -> value + " of " + a.target() + " shops";
            default -> value + " of " + a.target();
        };
    }

    /** Running totals while the history is replayed. */
    private static final class Totals {
        int rescues;
        int units;
        int unitsWithoutWeight;
        long grams;
        int drops;
        BigDecimal saved = BigDecimal.ZERO;
        final Set<Long> shops = new HashSet<>();
        LocalDate lastWeek;
        int runWeeks;
        int bestWeeks;

        void add(Rescue r, LocalDate day) {
            rescues++;
            units += r.units();
            grams += r.grams();
            if (!r.weightKnown()) unitsWithoutWeight += r.units();
            if (r.drop()) drops++;
            saved = saved.add(r.saved());
            shops.add(r.storeId());
            LocalDate week = weekOf(day);
            if (lastWeek == null || week.isAfter(lastWeek.plusWeeks(1))) runWeeks = 1;
            else if (week.equals(lastWeek.plusWeeks(1))) runWeeks++;
            lastWeek = week;
            bestWeeks = Math.max(bestWeeks, runWeeks);
        }

        long value(Achievement.Metric metric) {
            return switch (metric) {
                case RESCUES -> rescues;
                case GRAMS -> grams;
                case LEI_SAVED -> saved.longValue();
                case SHOPS -> shops.size();
                case DROPS -> drops;
                case BEST_WEEK_STREAK -> bestWeeks;
            };
        }
    }
}

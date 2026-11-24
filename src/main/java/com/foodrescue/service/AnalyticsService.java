package com.foodrescue.service;

import com.foodrescue.domain.FoodItem;
import com.foodrescue.domain.ProductTemplate;
import com.foodrescue.domain.Store;
import com.foodrescue.dto.AdminInsights;
import com.foodrescue.dto.StoreInsights;
import com.foodrescue.dto.StoreInsights.Day;
import com.foodrescue.dto.StoreInsights.ForecastDay;
import com.foodrescue.dto.StoreInsights.ForecastLine;
import com.foodrescue.dto.StoreInsights.Product;
import com.foodrescue.dto.StoreInsights.Recommendation;
import com.foodrescue.repository.FoodItemRepository;
import com.foodrescue.repository.ProductTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Smart Analytics: a store's results, a 7-day surplus forecast and recommendations, plus a
 * platform overview for administrators.
 *
 * <p>The figures are aggregates over many rows, so they are read with plain SQL through JDBC
 * ({@link NamedParameterJdbcTemplate}) instead of loading entities: one round trip per question,
 * and the database does the summing.</p>
 *
 * <p><b>Definitions.</b> Only offers that have ended in the period count, so a figure never
 * changes after the fact. "Offered" is what the offer was published with (adjusted when the owner
 * corrected the shelf); for offers older than that field it is rebuilt from their orders. Days are
 * calendar days in the store's own time zone.</p>
 */
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    static final int FORECAST_HISTORY_DAYS = 56;
    static final int MIN_FORECAST_HISTORY = 3;
    static final int AUDIENCE_MINIMUM = 5;

    private final NamedParameterJdbcTemplate jdbc;
    private final StoreService storeService;
    private final AutopilotService autopilotService;
    private final AlertService alertService;
    private final FoodItemRepository foodItemRepository;
    private final ProductTemplateRepository templateRepository;
    private final Clock clock;

    // ------------------------------------------------------------------------------------
    // One row per ended offer, with its order figures (the base of every number)
    // ------------------------------------------------------------------------------------

    /** What happened to one offer. Units are paid and free together unless named otherwise. */
    record OfferRow(long id, long storeId, Long templateId, String name, Instant createdAt, Instant expiresAt,
                    Instant pickupStartsAt, boolean drop, Integer unitWeightGrams, BigDecimal discountedPrice,
                    long left, Long published, long sold, long donated, long noShows, long open,
                    BigDecimal takings, BigDecimal savings, Instant lastOrderAt) {

        long collected() {
            return sold + donated;
        }

        /** Published units when known, otherwise rebuilt: on the shelf + every reserved unit. */
        long offered() {
            return published != null ? published : left + sold + donated + noShows + open;
        }

        boolean soldOut() {
            return left == 0 && collected() + noShows > 0;
        }
    }

    private static final String OFFER_ROWS = """
            select f.id, f.store_id, f.template_id, f.name, f.created_at, f.expires_at, f.pickup_starts_at,
                   f.rescue_drop, f.unit_weight_grams, f.discounted_price,
                   f.quantity_available + f.pay_it_forward_quantity as left_units,
                   f.quantity_published + f.free_published as published,
                   coalesce(sum(case when o.status = 'PICKED_UP' and o.type = 'PURCHASE' then o.quantity end), 0) as sold,
                   coalesce(sum(case when o.status = 'PICKED_UP' and o.type = 'PAY_IT_FORWARD' then o.quantity end), 0) as donated,
                   coalesce(sum(case when o.status = 'EXPIRED' then o.quantity end), 0) as no_shows,
                   coalesce(sum(case when o.status = 'RESERVED' then o.quantity end), 0) as open_units,
                   coalesce(sum(case when o.status = 'PICKED_UP' then o.total_price end), 0) as takings,
                   coalesce(sum(case when o.status = 'PICKED_UP' and o.type = 'PURCHASE'
                                     then o.quantity * (f.original_price - f.discounted_price) end), 0) as savings,
                   max(case when o.status <> 'CANCELLED' then o.created_at end) as last_order_at
            from food_item f
            left join orders o on o.food_item_id = f.id
            where f.status <> 'DRAFT' and f.expires_at > :from and f.expires_at <= :to
              and (cast(:storeId as bigint) is null or f.store_id = :storeId)
            group by f.id, f.store_id, f.template_id, f.name, f.created_at, f.expires_at, f.pickup_starts_at,
                     f.rescue_drop, f.unit_weight_grams, f.discounted_price, f.quantity_available,
                     f.pay_it_forward_quantity, f.quantity_published, f.free_published
            order by f.expires_at
            """;

    List<OfferRow> offerRows(Long storeId, Instant from, Instant to) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("storeId", storeId, java.sql.Types.BIGINT)
                .addValue("from", OffsetDateTime.ofInstant(from, java.time.ZoneOffset.UTC))
                .addValue("to", OffsetDateTime.ofInstant(to, java.time.ZoneOffset.UTC));
        return jdbc.query(OFFER_ROWS, p, (rs, n) -> new OfferRow(
                rs.getLong("id"), rs.getLong("store_id"), (Long) rs.getObject("template_id", Long.class),
                rs.getString("name"), instant(rs, "created_at"), instant(rs, "expires_at"),
                instant(rs, "pickup_starts_at"), rs.getBoolean("rescue_drop"),
                (Integer) rs.getObject("unit_weight_grams", Integer.class), rs.getBigDecimal("discounted_price"),
                rs.getLong("left_units"), (Long) rs.getObject("published", Long.class),
                rs.getLong("sold"), rs.getLong("donated"), rs.getLong("no_shows"), rs.getLong("open_units"),
                rs.getBigDecimal("takings"), rs.getBigDecimal("savings"), instant(rs, "last_order_at")));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    // ------------------------------------------------------------------------------------
    // Store insights
    // ------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public StoreInsights insights(Long ownerId, Long storeId, int days) {
        Store store = storeService.requireOwnedStore(ownerId, storeId);
        ZoneId zone = store.zone();
        Instant now = clock.instant();
        LocalDate to = LocalDate.ofInstant(now, zone);
        LocalDate from = to.minusDays(days - 1L);
        Instant start = from.atStartOfDay(zone).toInstant();

        List<OfferRow> rows = offerRows(storeId, start, now);
        StoreInsights.Totals totals = totals(rows);
        List<Day> daily = daily(rows, from, to, zone);
        List<Product> products = products(rows, zone);
        StoreInsights.Timing timing = timing(storeId, start, now, zone);
        List<ForecastLine> forecast = forecast(store, now);
        return new StoreInsights(from, to, store.getTimeZone(), totals, daily, products, timing, drops(rows),
                audience(store), recommend(rows, products, timing, totals, daily, zone,
                templateRepository.findByStoreIdAndArchivedFalseOrderByNameAsc(storeId)), forecast);
    }

    static StoreInsights.Totals totals(List<OfferRow> rows) {
        long offered = 0, collected = 0, donated = 0, leftover = 0, noShows = 0, weighed = 0;
        double kg = 0;
        BigDecimal takings = BigDecimal.ZERO, savings = BigDecimal.ZERO;
        for (OfferRow r : rows) {
            offered += r.offered();
            collected += r.collected();
            donated += r.donated();
            leftover += r.left();
            noShows += r.noShows();
            takings = takings.add(r.takings());
            savings = savings.add(r.savings());
            if (r.unitWeightGrams() != null) {
                kg += r.collected() * r.unitWeightGrams() / 1000.0;
                weighed += r.collected();
            }
        }
        return new StoreInsights.Totals(offered, collected, donated, leftover, noShows,
                percent(collected, offered), percent(noShows, noShows + collected),
                takings.setScale(2, RoundingMode.HALF_UP), savings.setScale(2, RoundingMode.HALF_UP),
                weighed == 0 ? null : Math.round(kg * 10) / 10.0,
                collected == 0 ? 0 : (int) Math.round(100.0 * weighed / collected), rows.size());
    }

    static Integer percent(long part, long whole) {
        return whole == 0 ? null : (int) Math.round(100.0 * part / whole);
    }

    static List<Day> daily(List<OfferRow> rows, LocalDate from, LocalDate to, ZoneId zone) {
        Map<LocalDate, long[]> byDay = new TreeMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) byDay.put(d, new long[3]);
        for (OfferRow r : rows) {
            long[] t = byDay.get(LocalDate.ofInstant(r.expiresAt(), zone));
            if (t == null) continue;
            t[0] += r.offered();
            t[1] += r.collected();
            t[2] += r.left();
        }
        List<Day> days = new ArrayList<>();
        byDay.forEach((d, t) -> days.add(new Day(d, t[0], t[1], t[2])));
        return days;
    }

    /** One line per saved product (one-off offers grouped by name), most offered first. */
    static List<Product> products(List<OfferRow> rows, ZoneId zone) {
        Map<String, List<OfferRow>> groups = new LinkedHashMap<>();
        for (OfferRow r : rows) {
            if (r.drop()) continue;
            groups.computeIfAbsent(r.templateId() != null ? "t" + r.templateId() : "n" + r.name(), k -> new ArrayList<>()).add(r);
        }
        List<Product> products = new ArrayList<>();
        groups.values().forEach(g -> {
            long offered = 0, collected = 0, left = 0, noShows = 0;
            int soldOut = 0;
            java.util.Set<LocalDate> days = new java.util.HashSet<>();
            for (OfferRow r : g) {
                offered += r.offered();
                collected += r.collected();
                left += r.left();
                noShows += r.noShows();
                if (r.soldOut()) soldOut++;
                days.add(LocalDate.ofInstant(r.expiresAt(), zone));
            }
            Integer sellThrough = percent(collected, offered);
            Integer noShowPercent = percent(noShows, noShows + collected);
            String flag = null;
            if (g.size() >= 2 && soldOut * 2 >= g.size()) flag = "Sells out";
            else if (sellThrough != null && sellThrough < 60 && g.size() >= 2) flag = "Often left over";
            if (noShowPercent != null && noShowPercent >= 20 && noShows >= 3) flag = flag == null ? "Many no-shows" : flag + " · many no-shows";
            OfferRow latest = g.get(g.size() - 1);
            products.add(new Product(latest.templateId(), latest.name(), days.size(), offered, collected, left, soldOut,
                    sellThrough, noShowPercent, flag));
        });
        products.sort(Comparator.comparingLong(Product::offered).reversed());
        return products;
    }

    /** When customers reserve (hour of day) and when in the pickup window they collect. */
    StoreInsights.Timing timing(Long storeId, Instant from, Instant to, ZoneId zone) {
        int[] hours = new int[24];
        int[] quarters = new int[4];
        int[] measured = new int[1];
        jdbc.query("""
                select o.created_at, o.picked_up_at, f.pickup_starts_at, f.expires_at
                from orders o join food_item f on f.id = o.food_item_id
                where f.store_id = :storeId and o.status <> 'CANCELLED' and o.created_at >= :from and o.created_at < :to
                """, new MapSqlParameterSource().addValue("storeId", storeId)
                .addValue("from", OffsetDateTime.ofInstant(from, java.time.ZoneOffset.UTC))
                .addValue("to", OffsetDateTime.ofInstant(to, java.time.ZoneOffset.UTC)), rs -> {
            hours[LocalTime.ofInstant(instant(rs, "created_at"), zone).getHour()]++;
            Instant picked = instant(rs, "picked_up_at"), start = instant(rs, "pickup_starts_at"), end = instant(rs, "expires_at");
            if (picked != null && start != null && end.isAfter(start)) {
                quarters[quarter(picked, start, end)]++;
                measured[0]++;
            }
        });
        return new StoreInsights.Timing(hours, quarters, measured[0]);
    }

    static int quarter(Instant picked, Instant start, Instant end) {
        double share = (double) Duration.between(start, picked).toMillis() / Duration.between(start, end).toMillis();
        return (int) Math.max(0, Math.min(3, Math.floor(share * 4)));
    }

    StoreInsights.Drops drops(List<OfferRow> rows) {
        List<OfferRow> drops = rows.stream().filter(OfferRow::drop).toList();
        long offered = drops.stream().mapToLong(OfferRow::offered).sum();
        long collected = drops.stream().mapToLong(OfferRow::collected).sum();
        List<Long> minutes = drops.stream().filter(OfferRow::soldOut).filter(r -> r.lastOrderAt() != null)
                .map(r -> Duration.between(r.createdAt(), r.lastOrderAt()).toMinutes()).sorted().toList();
        int alerted = 0, reserved = 0;
        if (!drops.isEmpty()) {
            Map<String, Object> counts = jdbc.queryForMap("""
                    select count(distinct n.id) as alerted,
                           count(distinct case when o.id is not null then n.id end) as reserved
                    from notification n
                    left join orders o on o.food_item_id = n.food_item_id and o.user_id = n.user_id and o.status <> 'CANCELLED'
                    where n.kind = 'DROP' and n.food_item_id in (:ids)
                    """, new MapSqlParameterSource("ids", drops.stream().map(OfferRow::id).toList()));
            alerted = ((Number) counts.get("alerted")).intValue();
            reserved = ((Number) counts.get("reserved")).intValue();
        }
        return new StoreInsights.Drops(drops.size(), offered, collected, percent(collected, offered),
                minutes.isEmpty() ? null : minutes.get(minutes.size() / 2).intValue(), alerted, reserved);
    }

    StoreInsights.Audience audience(Store store) {
        MapSqlParameterSource p = new MapSqlParameterSource("storeId", store.getId());
        int followers = jdbc.queryForObject("select count(*) from favourite_store where store_id = :storeId", p, Integer.class);
        int fans = jdbc.queryForObject("""
                select count(distinct fp.user_id) from favourite_product fp
                join product_template t on t.id = fp.template_id where t.store_id = :storeId
                """, p, Integer.class);
        return new StoreInsights.Audience(atLeast(followers), atLeast(fans), atLeast(alertService.audience(store)));
    }

    /** Small numbers are hidden: with 2 followers, a shop could guess who they are. */
    static Integer atLeast(int n) {
        return n >= AUDIENCE_MINIMUM ? n : null;
    }

    // ------------------------------------------------------------------------------------
    // Recommendations: plain rules, each with its evidence
    // ------------------------------------------------------------------------------------

    static List<Recommendation> recommend(List<OfferRow> rows, List<Product> products, StoreInsights.Timing timing,
                                          StoreInsights.Totals totals, List<Day> daily, ZoneId zone,
                                          List<ProductTemplate> templates) {
        List<Recommendation> out = new ArrayList<>();
        for (Product p : products) {
            if (p.templateId() == null || p.days() < 3) continue;
            if (p.soldOutDays() >= 3 && p.soldOutDays() * 2 >= p.days()) {
                // How long before the end of pickup the last unit went, on sold-out days.
                List<Long> early = rows.stream().filter(r -> p.templateId().equals(r.templateId()) && r.soldOut()
                                && r.lastOrderAt() != null)
                        .map(r -> Duration.between(r.lastOrderAt(), r.expiresAt()).toMinutes()).sorted().toList();
                long median = early.isEmpty() ? 0 : early.get(early.size() / 2);
                long more = Math.max(1, Math.round(p.offered() / (double) p.days() * 0.2));
                out.add(new Recommendation("SELLS_OUT", "Offer " + more + " more " + p.name(),
                        "Sold out on " + p.soldOutDays() + " of " + p.days() + " days"
                                + (median >= 30 ? ", usually " + duration(median) + " before pickup ended." : "."),
                        "Customers were still coming. The Autopilot already suggests more; check you can supply it.",
                        "SETUP", p.templateId()));
            } else if (p.sellThroughPercent() != null && p.sellThroughPercent() < 60) {
                out.add(new Recommendation("LEFT_OVER", "Less " + p.name() + " left over",
                        (100 - p.sellThroughPercent()) + "% went unsold (" + p.leftover() + " of " + p.offered() + " units).",
                        "Offer fewer, lower the price, or bundle leftovers into a Rescue Drop earlier in the evening.",
                        "EDIT_PRODUCT", p.templateId()));
            }
        }
        int[] q = timing.pickupsByQuarter();
        if (timing.pickupsMeasured() >= 10 && q[3] * 100 >= 60 * timing.pickupsMeasured()) {
            out.add(new Recommendation("LATE_PICKUPS", "Most customers come at the end of the window",
                    Math.round(100.0 * q[3] / timing.pickupsMeasured()) + "% of pickups were in the last quarter of the pickup window.",
                    "Starting pickup later keeps the counter free earlier and may reduce no-shows.", "EDIT_PRODUCT", null));
        }
        if (totals.noShows() >= 5 && totals.noShowPercent() != null && totals.noShowPercent() >= 15) {
            out.add(new Recommendation("NO_SHOWS", totals.noShowPercent() + "% of reservations weren't collected",
                    totals.noShows() + " reserved units were never picked up.",
                    "Customers now get a reminder before pickup closes. Shorter pickup windows also help.", null, null));
        }
        List<ProductTemplate> unweighed = templates.stream().filter(t -> t.getUnitWeightGrams() == null).toList();
        if (!unweighed.isEmpty() && totals.collected() > 0) {
            out.add(new Recommendation("ADD_WEIGHTS", "Add weights to see kg of food rescued",
                    unweighed.size() + (unweighed.size() == 1 ? " product has" : " products have") + " no weight yet, e.g. "
                            + unweighed.get(0).getName() + ".",
                    "An estimate per unit is enough.", "EDIT_PRODUCT", unweighed.get(0).getId()));
        }
        List<Day> recent = daily.subList(Math.max(0, daily.size() - 14), daily.size());
        long empty = recent.stream().limit(Math.max(0, recent.size() - 1)).filter(d -> d.offered() == 0).count();
        if (!templates.isEmpty() && recent.size() >= 7 && empty >= 4) {
            out.add(new Recommendation("QUIET_DAYS", "No offers on " + empty + " of the last " + (recent.size() - 1) + " days",
                    "Surplus that isn't published goes to waste.",
                    "Turn on the daily reminder, and use the Autopilot to publish in under a minute.", "REMINDER", null));
        }
        return out.stream().limit(6).toList();
    }

    private static String duration(long minutes) {
        return minutes < 120 ? minutes + " minutes" : Math.round(minutes / 60.0) + " hours";
    }

    // ------------------------------------------------------------------------------------
    // Forecast: next 7 days per product, with its own track record
    // ------------------------------------------------------------------------------------

    List<ForecastLine> forecast(Store store, Instant now) {
        ZoneId zone = store.zone();
        LocalDate today = LocalDate.ofInstant(now, zone);
        List<FoodItem> past = foodItemRepository.findEndedTemplateOffers(store.getId(),
                today.minusDays(FORECAST_HISTORY_DAYS).atStartOfDay(zone).toInstant(), now);
        Map<Long, AutopilotService.Units> units = autopilotService.units(past.stream().map(FoodItem::getId).toList());
        Map<Long, List<AutopilotService.Day>> byTemplate = new HashMap<>();
        for (FoodItem item : past) {
            byTemplate.computeIfAbsent(item.getTemplate().getId(), id -> new ArrayList<>())
                    .add(AutopilotService.Day.of(item, units.getOrDefault(item.getId(), AutopilotService.Units.NONE), zone));
        }
        List<ForecastLine> lines = new ArrayList<>();
        for (ProductTemplate t : templateRepository.findByStoreIdAndArchivedFalseOrderByNameAsc(store.getId())) {
            List<AutopilotService.Day> history = new ArrayList<>(byTemplate.getOrDefault(t.getId(), List.of()));
            history.sort(Comparator.comparing(AutopilotService.Day::date));
            lines.add(forecastLine(t.getId(), t.getName(), history, today));
        }
        return lines;
    }

    /** History sorted oldest first. Days after today: tomorrow and the 6 days after. */
    static ForecastLine forecastLine(Long templateId, String name, List<AutopilotService.Day> history, LocalDate today) {
        if (history.size() < MIN_FORECAST_HISTORY) {
            return new ForecastLine(templateId, name, history.size(), List.of(), 0, 0, false);
        }
        List<ForecastDay> days = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            LocalDate date = today.plusDays(i);
            double[] e = estimate(history, date.getDayOfWeek());
            days.add(new ForecastDay(date, (int) Math.max(0, Math.floor(e[0] - e[1])), (int) Math.round(e[0]),
                    (int) Math.ceil(e[0] + e[1])));
        }
        int hits = 0, checked = 0;
        for (int i = Math.max(MIN_FORECAST_HISTORY, history.size() - 14); i < history.size(); i++) {
            AutopilotService.Day actual = history.get(i);
            long predicted = Math.round(estimate(history.subList(0, i), actual.date().getDayOfWeek())[0]);
            if (Math.abs(predicted - actual.demand()) <= Math.max(1, Math.round(actual.demand() * 0.2))) hits++;
            checked++;
        }
        long soldOut = history.stream().filter(AutopilotService.Day::soldOut).count();
        return new ForecastLine(templateId, name, history.size(), days, hits, checked, soldOut * 2 >= history.size());
    }

    /**
     * Expected demand for a weekday: the same-weekday average pulled towards the overall average
     * (as if the overall average were 2 more same-weekday samples), so a single odd Saturday does
     * not swing the forecast. The spread is the standard deviation, at least one unit.
     *
     * @return {expected, spread}
     */
    static double[] estimate(List<AutopilotService.Day> history, DayOfWeek weekday) {
        double all = history.stream().mapToInt(AutopilotService.Day::demand).average().orElse(0);
        List<Integer> same = history.stream().filter(d -> d.date().getDayOfWeek() == weekday)
                .map(AutopilotService.Day::demand).toList();
        double expected = (same.stream().mapToInt(Integer::intValue).sum() + 2 * all) / (same.size() + 2);
        double variance = history.stream().mapToDouble(d -> Math.pow(d.demand() - all, 2)).average().orElse(0);
        return new double[]{expected, Math.max(1, Math.sqrt(variance))};
    }

    // ------------------------------------------------------------------------------------
    // CSV export and the administrator's overview
    // ------------------------------------------------------------------------------------

    /** One line per ended offer, for the shop's own bookkeeping. */
    @Transactional(readOnly = true)
    public void exportCsv(Long ownerId, Long storeId, int days, Writer out) throws IOException {
        Store store = storeService.requireOwnedStore(ownerId, storeId);
        ZoneId zone = store.zone();
        Instant now = clock.instant();
        Instant start = LocalDate.ofInstant(now, zone).minusDays(days - 1L).atStartOfDay(zone).toInstant();
        out.write("date,product,rescue_drop,offered,collected,donated,no_shows,left_over,price_lei,takings_lei,kg_collected\n");
        for (OfferRow r : offerRows(storeId, start, now)) {
            out.write(String.join(",", LocalDate.ofInstant(r.expiresAt(), zone).toString(), csv(r.name()),
                    String.valueOf(r.drop()), String.valueOf(r.offered()), String.valueOf(r.collected()),
                    String.valueOf(r.donated()), String.valueOf(r.noShows()), String.valueOf(r.left()),
                    r.discountedPrice().toPlainString(), r.takings().setScale(2, RoundingMode.HALF_UP).toPlainString(),
                    r.unitWeightGrams() == null ? "" : String.valueOf(r.collected() * r.unitWeightGrams() / 1000.0)));
            out.write("\n");
        }
    }

    /**
     * Quotes a text cell. Cells starting with = + - @ are prefixed with ' so a spreadsheet never
     * runs them as formulas (a product name is typed by the shop).
     */
    static String csv(String value) {
        String v = value == null ? "" : value;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) v = "'" + v;
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }

    @Transactional(readOnly = true)
    public AdminInsights adminOverview() {
        Instant now = clock.instant();
        Instant from = now.minus(Duration.ofDays(28));
        List<OfferRow> rows = offerRows(null, from, now);
        StoreInsights.Totals totals = totals(rows);
        Map<String, Object> people = jdbc.queryForMap("""
                select count(distinct o.user_id) as customers, count(distinct f.store_id) as stores
                from orders o join food_item f on f.id = o.food_item_id
                where o.status = 'PICKED_UP' and o.picked_up_at >= :from
                """, new MapSqlParameterSource("from", OffsetDateTime.ofInstant(from, java.time.ZoneOffset.UTC)));

        List<AdminInsights.StoreLine> quiet = jdbc.query("""
                select s.id, s.name, max(f.created_at) as last_offer
                from store s left join food_item f on f.store_id = s.id
                where s.active = true
                group by s.id, s.name
                having max(f.created_at) is null or max(f.created_at) < :since
                order by s.name
                """, new MapSqlParameterSource("since", OffsetDateTime.ofInstant(now.minus(Duration.ofDays(7)), java.time.ZoneOffset.UTC)),
                (rs, n) -> {
                    Instant last = instant(rs, "last_offer");
                    return new AdminInsights.StoreLine(rs.getLong("id"), rs.getString("name"),
                            last == null ? "Never published" : "Last offer " + Duration.between(last, now).toDays() + " days ago");
                });

        Map<Long, List<OfferRow>> byStore = new TreeMap<>();
        rows.forEach(r -> byStore.computeIfAbsent(r.storeId(), id -> new ArrayList<>()).add(r));
        Map<Long, String> names = new HashMap<>();
        jdbc.query("select id, name from store", rs -> { names.put(rs.getLong("id"), rs.getString("name")); });
        List<AdminInsights.StoreLine> attention = new ArrayList<>();
        byStore.forEach((id, list) -> {
            StoreInsights.Totals t = totals(list);
            if (t.offered() >= 20 && t.sellThroughPercent() != null && t.sellThroughPercent() < 50) {
                attention.add(new AdminInsights.StoreLine(id, names.get(id), "Only " + t.sellThroughPercent() + "% of offered food collected"));
            } else if (t.noShows() + t.collected() >= 10 && t.noShowPercent() != null && t.noShowPercent() >= 20) {
                attention.add(new AdminInsights.StoreLine(id, names.get(id), t.noShowPercent() + "% of reservations not collected"));
            }
        });
        return new AdminInsights(totals, ((Number) people.get("customers")).intValue(), ((Number) people.get("stores")).intValue(),
                quiet, attention);
    }
}

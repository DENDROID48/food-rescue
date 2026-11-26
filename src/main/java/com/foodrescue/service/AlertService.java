package com.foodrescue.service;

import com.foodrescue.domain.DropAlertPreference;
import com.foodrescue.domain.FoodItem;
import com.foodrescue.domain.Notification;
import com.foodrescue.domain.NotificationKind;
import com.foodrescue.domain.RadarProfile;
import com.foodrescue.domain.Store;
import com.foodrescue.dto.DropAlertPreferenceRequest;
import com.foodrescue.dto.DropAlertPreferenceResponse;
import com.foodrescue.dto.NearbyFoodItemResponse;
import com.foodrescue.dto.NotificationResponse;
import com.foodrescue.dto.RadarResponse;
import com.foodrescue.push.PushMessage;
import com.foodrescue.push.PushRequested;
import com.foodrescue.repository.DropAlertPreferenceRepository;
import com.foodrescue.repository.FavouriteProductRepository;
import com.foodrescue.repository.FavouriteStoreRepository;
import com.foodrescue.repository.NotificationRepository;
import com.foodrescue.repository.RadarProfileRepository;
import com.foodrescue.util.GeoUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Every customer alert: Rescue Drops nearby, favourites on offer, and the daily digest. Alerts go
 * to the in-app inbox and, for customers who allowed it, as Web Push messages (no paid service).
 *
 * <p><b>One set of anti-spam rules for every alert</b> (except the digest the customer asked for):</p>
 * <ul>
 *   <li>at most one alert per shop every {@link #SAME_STORE_GAP}, so a drop and a favourite from
 *       the same shop arrive as one;</li>
 *   <li>at most the customer's daily maximum in any 24 hours (3 by default);</li>
 *   <li>half of that while the customer leaves alerts unopened (see {@link RadarService#throttled});</li>
 *   <li>during the customer's quiet hours, alerts wait silently in the inbox (no push).</li>
 * </ul>
 *
 * <p>Alerts are written in the same transaction as the event that caused them; push messages
 * are sent only after that transaction commits.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AlertService {

    /** The largest radius a customer can choose; bounds the database search around a store. */
    static final double MAX_RADIUS_KM = 10;
    static final Duration SAME_STORE_GAP = Duration.ofHours(2);
    static final int DEFAULT_MAX_PER_DAY = 3;
    /** Old alerts are deleted after this time. */
    static final Duration KEEP_FOR = Duration.ofDays(30);
    /** A digest is not sent more than this long after its time (for example after downtime). */
    static final Duration DIGEST_GRACE = Duration.ofHours(3);
    private static final List<NotificationKind> COUNTED = List.of(NotificationKind.DROP, NotificationKind.FAVOURITE);
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    private final DropAlertPreferenceRepository preferenceRepository;
    private final NotificationRepository notificationRepository;
    private final FavouriteStoreRepository favouriteStoreRepository;
    private final FavouriteProductRepository favouriteProductRepository;
    private final RadarProfileRepository profileRepository;
    private final RadarService radarService;
    private final ApplicationEventPublisher events;
    private final com.foodrescue.repository.OrderRepository orderRepository;
    private final org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc;
    private final com.foodrescue.i18n.Translator translator;
    private final Clock clock;

    // ------------------------------------------------------------------------------------
    // Rescue Drops
    // ------------------------------------------------------------------------------------

    /**
     * Customers who would be alerted about a drop at this store because they are near, before the
     * anti-spam limits. Shown to the owner before launching, as a number only.
     */
    @Transactional(readOnly = true)
    public int audience(Store store) {
        return nearby(store).size();
    }

    /**
     * Alerts about a new drop: opted-in customers nearby, and customers who follow the shop or the
     * product and asked for favourite alerts (wherever they are).
     *
     * @return how many customers were alerted
     */
    @Transactional
    public int alertAbout(FoodItem drop) {
        Store store = drop.getStore();
        Set<Long> recipients = new HashSet<>();
        nearby(store).forEach(p -> recipients.add(p.getUserId()));
        Set<Long> fans = new HashSet<>(favouriteStoreRepository.followersOf(store.getId()));
        if (drop.getTemplate() != null) {
            favouriteProductRepository.followersOf(List.of(drop.getTemplate().getId())).forEach(r -> fans.add((Long) r[0]));
        }
        recipients.addAll(wantFavouriteAlerts(fans));

        String ends = LocalTime.ofInstant(drop.getExpiresAt(), store.zone()).format(HHMM);
        Message message = new Message("Rescue Drop at " + store.getName(), describe(drop) + " · until " + ends);
        int sent = deliver(store, drop, NotificationKind.DROP, recipients.stream()
                .collect(Collectors.toMap(Function.identity(), id -> message)));
        log.info("Drop {} of store {}: alerted {} of {} interested customer(s)", drop.getId(), store.getId(), sent, recipients.size());
        return sent;
    }

    private static String describe(FoodItem drop) {
        if (drop.getQuantityAvailable() == 0) {
            return drop.getPayItForwardQuantity() + " × " + drop.getName() + " (free, Pay-It-Forward)";
        }
        return drop.getQuantityAvailable() + " × " + drop.getName() + " for " + drop.getDiscountedPrice().toPlainString() + " lei";
    }

    /** Opted-in customers whose own radius reaches the store; never the store's own owner. */
    private List<DropAlertPreference> nearby(Store store) {
        GeoUtils.BoundingBox box = GeoUtils.boundingBox(store.getLatitude(), store.getLongitude(), MAX_RADIUS_KM);
        return preferenceRepository.findEnabledInBox(box.minLat(), box.maxLat(), box.minLng(), box.maxLng()).stream()
                .filter(p -> p.distanceKmTo(store) <= p.getRadiusKm())
                .toList();
    }

    // ------------------------------------------------------------------------------------
    // Favourites
    // ------------------------------------------------------------------------------------

    /**
     * Tells customers that products they marked as favourites are on offer now. Several
     * favourites from the same shop become one alert ("2 of your favourites at …").
     *
     * @param offers live offers of one store, just published
     */
    @Transactional
    public int alertFavouritesAbout(List<FoodItem> offers) {
        List<FoodItem> fromTemplates = offers.stream().filter(o -> o.getTemplate() != null && !o.isRescueDrop()).toList();
        if (fromTemplates.isEmpty()) {
            return 0;
        }
        Store store = fromTemplates.get(0).getStore();
        Map<Long, FoodItem> byTemplate = new LinkedHashMap<>();
        fromTemplates.forEach(o -> byTemplate.put(o.getTemplate().getId(), o));

        Set<String> alreadyTold = new HashSet<>();
        for (Object[] row : notificationRepository.notifiedAbout(fromTemplates.stream().map(FoodItem::getId).toList())) {
            alreadyTold.add(row[0] + ":" + row[1]);
        }
        Map<Long, List<FoodItem>> perUser = new LinkedHashMap<>();
        for (Object[] row : favouriteProductRepository.followersOf(byTemplate.keySet())) {
            FoodItem offer = byTemplate.get((Long) row[1]);
            if (!alreadyTold.contains(row[0] + ":" + offer.getId())) {
                perUser.computeIfAbsent((Long) row[0], id -> new ArrayList<>()).add(offer);
            }
        }
        perUser.keySet().retainAll(wantFavouriteAlerts(perUser.keySet()));

        Map<Long, Message> messages = new HashMap<>();
        Map<Long, FoodItem> subject = new HashMap<>();
        perUser.forEach((userId, items) -> {
            FoodItem first = items.get(0);
            String window = first.getPickupStartsAt() == null ? ""
                    : " · pickup " + LocalTime.ofInstant(first.getPickupStartsAt(), store.zone()).format(HHMM)
                    + "–" + LocalTime.ofInstant(first.getExpiresAt(), store.zone()).format(HHMM);
            messages.put(userId, items.size() == 1
                    ? new Message("Your favourite at " + store.getName(),
                    first.getName() + " is on offer for " + first.getDiscountedPrice().toPlainString() + " lei" + window)
                    : new Message(items.size() + " of your favourites at " + store.getName(),
                    items.stream().map(FoodItem::getName).collect(Collectors.joining(", ")) + " are on offer" + window));
            subject.put(userId, first);
        });
        return deliverEach(store, subject, NotificationKind.FAVOURITE, messages);
    }

    /** The customers among these who asked for favourite alerts. */
    private Set<Long> wantFavouriteAlerts(Set<Long> userIds) {
        if (userIds.isEmpty()) return Set.of();
        return profileRepository.findByUserIdIn(userIds).stream()
                .filter(RadarProfile::isNotifyFavourites).map(RadarProfile::getUserId).collect(Collectors.toSet());
    }

    // ------------------------------------------------------------------------------------
    // Daily digest
    // ------------------------------------------------------------------------------------

    /**
     * Every few minutes: customers whose digest time has just passed (in their own zone) get one
     * summary of the best offers near their alert place. At most once a day, never in quiet hours
     * (it waits until they end, within {@link #DIGEST_GRACE}).
     */
    @Scheduled(fixedDelayString = "${foodrescue.digest-job-delay-ms:300000}", initialDelay = 60_000)
    @Transactional
    public int sendDigests() {
        Instant now = clock.instant();
        List<PushMessage> pushes = new ArrayList<>();
        int sent = 0;
        for (RadarProfile p : profileRepository.findByDigestTimeIsNotNull()) {
            ZoneId zone = p.zone();
            LocalDate today = LocalDate.ofInstant(now, zone);
            Instant due = today.atTime(p.getDigestTime()).atZone(zone).toInstant();
            if (today.equals(p.getLastDigestOn()) || now.isBefore(due) || p.isQuietAt(now)) {
                continue;
            }
            p.setLastDigestOn(today);
            DropAlertPreference place = preferenceRepository.findById(p.getUserId()).orElse(null);
            if (place == null || now.isAfter(due.plus(DIGEST_GRACE))) {
                continue;
            }
            Message message = digest(p.getUserId(), place);
            if (message == null) {
                continue;
            }
            Notification n = notification(p.getUserId(), null, null, NotificationKind.DIGEST, message, now);
            notificationRepository.save(n);
            pushes.add(push(n));
            sent++;
        }
        if (!pushes.isEmpty()) {
            events.publishEvent(new PushRequested(translator.localize(pushes))); // in each person's language
        }
        return sent;
    }

    /** "3 offers within 2 km: Sourdough loaf at Corner Bakery, …", personal picks first. */
    private Message digest(Long userId, DropAlertPreference place) {
        RadarResponse radar = radarService.recommend(userId, place.getLatitude(), place.getLongitude(), place.getRadiusKm());
        // Fresh search for the names; the radar only returns ids.
        Map<Long, NearbyFoodItemResponse> items = new LinkedHashMap<>();
        radarService.nearby(place.getLatitude(), place.getLongitude(), place.getRadiusKm()).forEach(i -> items.put(i.id(), i));
        if (items.isEmpty()) {
            return null;
        }
        List<NearbyFoodItemResponse> ordered = new ArrayList<>();
        radar.picks().forEach(pick -> { if (items.containsKey(pick.itemId())) ordered.add(items.get(pick.itemId())); });
        items.values().stream().filter(i -> !ordered.contains(i)).forEach(ordered::add);
        String listed = ordered.stream().limit(2).map(i -> i.name() + " at " + i.storeName()).collect(Collectors.joining(", "));
        int more = ordered.size() - 2;
        String radius = (place.getRadiusKm() % 1 == 0 ? String.valueOf((int) place.getRadiusKm()) : String.valueOf(place.getRadiusKm()));
        return new Message(ordered.size() + (ordered.size() == 1 ? " offer" : " offers") + " near you today",
                listed + (more > 0 ? " and " + more + " more" : "") + " · within " + radius + " km");
    }

    // ------------------------------------------------------------------------------------
    // Pickup reminders
    // ------------------------------------------------------------------------------------

    /** Reminders go out between 30 and 5 minutes before a reservation's pickup closes. */
    static final Duration REMIND_BEFORE = Duration.ofMinutes(30);
    static final Duration REMIND_AT_LEAST = Duration.ofMinutes(5);
    /** No reminder for a reservation made shortly before its deadline: the customer just made it. */
    static final Duration REMIND_ONLY_IF_RESERVED_BEFORE = Duration.ofMinutes(45);

    /**
     * Every minute: customers with a reservation about to close get a reminder, unless they turned
     * reminders off (they are on by default). It is not limited like other alerts, because it is
     * about food the customer already reserved, but it stays silent during quiet hours.
     *
     * <p>Each order is claimed with one SQL UPDATE ("... where reminder_sent_at is null"), so even
     * two servers running this job at the same moment can never send the same reminder twice.</p>
     */
    @Scheduled(fixedDelayString = "${foodrescue.reminder-job-delay-ms:60000}", initialDelay = 30_000)
    @Transactional
    public int sendPickupReminders() {
        Instant now = clock.instant();
        List<com.foodrescue.domain.Order> due = orderRepository.dueForReminder(now.plus(REMIND_AT_LEAST), now.plus(REMIND_BEFORE))
                .stream().filter(o -> !o.getCreatedAt().isAfter(o.getPickupDeadline().minus(REMIND_ONLY_IF_RESERVED_BEFORE)))
                .toList();
        if (due.isEmpty()) {
            return 0;
        }
        Map<Long, RadarProfile> profiles = new HashMap<>();
        profileRepository.findByUserIdIn(due.stream().map(o -> o.getUser().getId()).toList())
                .forEach(p -> profiles.put(p.getUserId(), p));
        List<Notification> saved = new ArrayList<>();
        for (com.foodrescue.domain.Order o : due) {
            Long userId = o.getUser().getId();
            RadarProfile profile = profiles.get(userId);
            if (profile != null && !profile.isPickupReminders()) {
                continue;
            }
            int claimed = jdbc.update("update orders set reminder_sent_at = :now where id = :id and reminder_sent_at is null",
                    new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                            .addValue("now", java.time.OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC))
                            .addValue("id", o.getId()));
            if (claimed == 0) {
                continue;
            }
            Store store = o.getFoodItem().getStore();
            String by = LocalTime.ofInstant(o.getPickupDeadline(), store.zone()).format(HHMM);
            // The pickup code is not put in the message: it could show on a locked screen.
            saved.add(notification(userId, store, o.getFoodItem(), NotificationKind.REMINDER,
                    new Message("Pick up by " + by + " at " + store.getName(),
                            o.getFoodItem().getName() + " × " + o.getQuantity() + " · your pickup code is in My orders"), now));
        }
        notificationRepository.saveAll(saved);
        List<PushMessage> pushes = saved.stream()
                .filter(n -> profiles.get(n.getUserId()) == null || !profiles.get(n.getUserId()).isQuietAt(now))
                .map(AlertService::push).toList();
        if (!pushes.isEmpty()) {
            events.publishEvent(new PushRequested(translator.localize(pushes))); // in each person's language
        }
        return saved.size();
    }

    // ------------------------------------------------------------------------------------
    // Delivery with the anti-spam rules
    // ------------------------------------------------------------------------------------

    private record Message(String title, String body) {
    }

    private int deliver(Store store, FoodItem item, NotificationKind kind, Map<Long, Message> messages) {
        Map<Long, FoodItem> subject = new HashMap<>();
        messages.keySet().forEach(id -> subject.put(id, item));
        return deliverEach(store, subject, kind, messages);
    }

    private int deliverEach(Store store, Map<Long, FoodItem> subject, NotificationKind kind, Map<Long, Message> messages) {
        Instant now = clock.instant();
        List<Long> ids = messages.keySet().stream().filter(id -> !id.equals(store.getOwner().getId())).toList();
        if (ids.isEmpty()) {
            return 0;
        }
        Map<Long, Long> recent = new HashMap<>();
        for (Object[] row : notificationRepository.countSince(ids, now.minus(Duration.ofHours(24)), COUNTED)) {
            recent.put((Long) row[0], (Long) row[1]);
        }
        Set<Long> sameStore = notificationRepository.alertedAboutStoreSince(ids, store.getId(), now.minus(SAME_STORE_GAP));
        Map<Long, Integer> maxPerDay = new HashMap<>();
        preferenceRepository.findAllById(ids).forEach(p -> maxPerDay.put(p.getUserId(), p.getMaxPerDay()));
        Set<Long> throttled = radarService.throttled(ids);
        Map<Long, RadarProfile> profiles = new HashMap<>();
        profileRepository.findByUserIdIn(ids).forEach(p -> profiles.put(p.getUserId(), p));

        List<Notification> saved = new ArrayList<>();
        List<PushMessage> pushes = new ArrayList<>();
        for (Long userId : ids) {
            int max = maxPerDay.getOrDefault(userId, DEFAULT_MAX_PER_DAY);
            if (throttled.contains(userId)) max = Math.max(1, max / 2);
            if (sameStore.contains(userId) || recent.getOrDefault(userId, 0L) >= max) {
                continue;
            }
            saved.add(notification(userId, store, subject.get(userId), kind, messages.get(userId), now));
        }
        notificationRepository.saveAll(saved);
        // Ids exist now, so each push can open its own alert. Quiet hours stay silent.
        for (Notification n : saved) {
            RadarProfile profile = profiles.get(n.getUserId());
            if (profile == null || !profile.isQuietAt(now)) pushes.add(push(n));
        }
        if (!pushes.isEmpty()) {
            events.publishEvent(new PushRequested(translator.localize(pushes))); // in each person's language
        }
        return saved.size();
    }

    private static Notification notification(Long userId, Store store, FoodItem item, NotificationKind kind, Message m,
                                             Instant now) {
        Notification n = new Notification();
        n.setUserId(userId);
        n.setStore(store);
        n.setFoodItem(item);
        n.setKind(kind);
        n.setTitle(truncate(m.title(), 150));
        n.setBody(truncate(m.body(), 300));
        n.setCreatedAt(now);
        return n;
    }

    /** Tapping the push message opens the app on this alert. */
    private static PushMessage push(Notification n) {
        return new PushMessage(n.getUserId(), n.getTitle(), n.getBody(), "/?alert=" + n.getId(), "alert-" + n.getId());
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    // ------------------------------------------------------------------------------------
    // Customer side: drop alert place and the inbox
    // ------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public DropAlertPreferenceResponse preference(Long userId) {
        return preferenceRepository.findById(userId).map(DropAlertPreferenceResponse::from)
                .orElse(DropAlertPreferenceResponse.NONE);
    }

    @Transactional
    public DropAlertPreferenceResponse savePreference(Long userId, DropAlertPreferenceRequest request) {
        DropAlertPreference p = preferenceRepository.findById(userId).orElseGet(() -> {
            DropAlertPreference fresh = new DropAlertPreference();
            fresh.setUserId(userId);
            return fresh;
        });
        p.setEnabled(request.enabled());
        p.setLatitude(request.latitude());
        p.setLongitude(request.longitude());
        p.setRadiusKm(request.radiusKm());
        p.setMaxPerDay(request.maxPerDay());
        p.setUpdatedAt(clock.instant());
        return DropAlertPreferenceResponse.from(preferenceRepository.save(p));
    }

    @Transactional(readOnly = true)
    public NotificationResponse.Inbox inbox(Long userId) {
        Instant now = clock.instant();
        List<NotificationResponse> items = notificationRepository
                .findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 30)).stream()
                .map(n -> NotificationResponse.from(n, now)).toList();
        return new NotificationResponse.Inbox(notificationRepository.countByUserIdAndReadAtIsNull(userId), items);
    }

    @Transactional
    public void markAllRead(Long userId) {
        notificationRepository.markAllRead(userId, clock.instant());
    }

    /** Nightly clean-up, so the inbox table does not grow forever. */
    @Scheduled(cron = "${foodrescue.notification-cleanup-cron:0 30 3 * * *}")
    @Transactional
    public void deleteOldNotifications() {
        int deleted = notificationRepository.deleteOlderThan(clock.instant().minus(KEEP_FOR));
        if (deleted > 0) {
            log.info("Deleted {} old notification(s)", deleted);
        }
    }
}

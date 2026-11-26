package com.foodrescue.service;

import com.foodrescue.config.NoShowProperties;
import com.foodrescue.domain.OrderStatus;
import com.foodrescue.domain.Store;
import com.foodrescue.exception.BusinessRuleException;
import com.foodrescue.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The no-show rule. Reserving food and not collecting it keeps it from someone else, so:
 * after {@code limitAt} missed pickups within {@code windowDays}, a customer may hold one
 * reservation at a time; after {@code pauseAt}, they cannot reserve or claim until the count
 * drops again. Everything is worked out from expired reservations, so limits lift by themselves
 * and cancelling in time never counts.
 */
@Service
@RequiredArgsConstructor
public class NoShowService {

    public enum Level { OK, WARNED, LIMITED, PAUSED }

    /** A customer's standing; {@code until} is when the current limit ends (null without one). */
    public record Standing(int noShows, int windowDays, int limitAt, int pauseAt, Level level, Instant until) {
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
            .withZone(ZoneId.of(Store.DEFAULT_TIME_ZONE));

    private final OrderRepository orderRepository;
    private final NoShowProperties properties;
    private final Clock clock;

    @Transactional(readOnly = true)
    public Standing standing(Long userId) {
        Duration window = Duration.ofDays(properties.windowDays());
        List<Instant> missed = orderRepository.noShowDeadlines(userId, clock.instant().minus(window));
        int n = missed.size();
        Level level;
        Instant until = null;
        if (n >= properties.pauseAt()) {
            level = Level.PAUSED;
            // Lifts when enough of them have left the window: the (n - pauseAt + 1)-th oldest.
            until = missed.get(n - properties.pauseAt()).plus(window);
        } else if (n >= properties.limitAt()) {
            level = Level.LIMITED;
            until = missed.get(n - properties.limitAt()).plus(window);
        } else {
            level = n > 0 ? Level.WARNED : Level.OK;
        }
        return new Standing(n, properties.windowDays(), properties.limitAt(), properties.pauseAt(), level, until);
    }

    /** Refuses a new reservation or free claim when the rule says so. */
    @Transactional(readOnly = true)
    public void check(Long userId) {
        Standing s = standing(userId);
        if (s.level() == Level.PAUSED) {
            throw new BusinessRuleException(HttpStatus.FORBIDDEN,
                    "Reserving is paused until " + DAY.format(s.until()) + " because of missed pickups");
        }
        if (s.level() == Level.LIMITED && orderRepository.existsByUserIdAndStatus(userId, OrderStatus.RESERVED)) {
            throw new BusinessRuleException(HttpStatus.CONFLICT,
                    "Because of missed pickups you can hold one reservation at a time. Collect or cancel the one you have first.");
        }
    }

    /** Missed pickups within the window, per customer (for the admin's list). */
    @Transactional(readOnly = true)
    public Map<Long, Integer> counts(Collection<Long> userIds) {
        Map<Long, Integer> out = new HashMap<>();
        if (userIds.isEmpty()) return out;
        Instant since = clock.instant().minus(Duration.ofDays(properties.windowDays()));
        for (Object[] row : orderRepository.noShowCounts(userIds, since)) {
            out.put((Long) row[0], ((Number) row[1]).intValue());
        }
        return out;
    }
}

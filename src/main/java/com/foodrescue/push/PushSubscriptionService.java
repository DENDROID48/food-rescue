package com.foodrescue.push;

import com.foodrescue.domain.PushSubscription;
import com.foodrescue.dto.PushSubscriptionRequest;
import com.foodrescue.repository.PushSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

/** Browsers subscribing to and leaving push messages. */
@Service
@RequiredArgsConstructor
public class PushSubscriptionService {

    private final PushSubscriptionRepository repository;
    private final WebPushSender sender;
    private final Clock clock;

    /**
     * Saves the browser's subscription for this user. A browser has one endpoint, so when another
     * account signs in on the same browser, the subscription moves to that account.
     */
    @Transactional
    public void subscribe(Long userId, PushSubscriptionRequest request) {
        sender.checkEndpoint(request.endpoint());
        sender.checkKeys(request.keys().p256dh(), request.keys().auth());
        PushSubscription s = repository.findByEndpoint(request.endpoint()).orElseGet(() -> {
            PushSubscription fresh = new PushSubscription();
            fresh.setEndpoint(request.endpoint());
            fresh.setCreatedAt(clock.instant());
            return fresh;
        });
        s.setUserId(userId);
        s.setP256dh(request.keys().p256dh());
        s.setAuth(request.keys().auth());
        s.setFailures(0);
        repository.save(s);
    }

    /** Called on sign-out, so the next person using this browser does not get these alerts. */
    @Transactional
    public void unsubscribe(Long userId, String endpoint) {
        repository.findByEndpoint(endpoint).filter(s -> s.getUserId().equals(userId)).ifPresent(repository::delete);
    }

    /** Sends a test message to every browser of this user, right away. */
    public int test(Long userId) {
        return sender.send(List.of(new PushMessage(userId, "Push notifications work",
                "You'll hear about Rescue Drops and favourites here.", "/", "test")));
    }
}

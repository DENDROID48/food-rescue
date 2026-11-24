package com.foodrescue.push;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foodrescue.config.PushProperties;
import com.foodrescue.domain.PushSubscription;
import com.foodrescue.exception.BusinessRuleException;
import com.foodrescue.repository.PushSubscriptionRepository;
import lombok.extern.slf4j.Slf4j;
import nl.martijndwars.webpush.Encoding;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Utils;
import org.apache.http.Header;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.util.EntityUtils;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.interfaces.ECPrivateKey;
import org.bouncycastle.jce.interfaces.ECPublicKey;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Sends Web Push messages straight to the browsers' own push services (Google, Mozilla, Apple,
 * Microsoft). It costs nothing and needs no account: the server proves who it is with a VAPID key
 * pair, and every message is encrypted for the receiving browser (RFC 8291).
 *
 * <p>Messages are sent after the transaction that created the alerts has committed, on a
 * background thread, so a slow push service never delays a store launching a drop. The in-app
 * inbox stays the source of truth: a failed push loses nothing.</p>
 *
 * <p>Subscriptions that the push service reports as gone (404/410) are deleted; others are
 * deleted after {@value #MAX_FAILURES} failures in a row.</p>
 */
@Component
@Slf4j
public class WebPushSender {

    static final int MAX_FAILURES = 5;
    /** How long a push service keeps a message for an offline device: drops are short-lived. */
    static final int TTL_SECONDS = 2 * 60 * 60;
    private static final Set<String> RESTRICTED_HEADERS = Set.of("content-length", "host", "connection", "expect", "upgrade");

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private final PushProperties properties;
    private final PushSubscriptionRepository repository;
    private final ObjectMapper json;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String publicKey;
    private final PushService vapid;

    public WebPushSender(PushProperties properties, PushSubscriptionRepository repository, ObjectMapper json, Clock clock)
            throws Exception {
        this.properties = properties;
        this.repository = repository;
        this.json = json;
        this.clock = clock;
        String pub = properties.publicKey(), priv = properties.privateKey();
        if (isBlank(pub) || isBlank(priv)) {
            String[] generated = generateKeys();
            pub = generated[0];
            priv = generated[1];
            log.warn("No VAPID keys configured (FOODRESCUE_PUSH_PUBLIC_KEY / FOODRESCUE_PUSH_PRIVATE_KEY): using temporary keys. "
                    + "Browsers have to subscribe again after every restart. Set fixed keys in production.");
        }
        this.publicKey = pub;
        this.vapid = new PushService(pub, priv, properties.subject());
    }

    /** The key browsers need to subscribe ("applicationServerKey"). */
    public String publicKey() {
        return publicKey;
    }

    /** A new P-256 key pair as base64url strings [public, private]. */
    public static String[] generateKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("ECDH", BouncyCastleProvider.PROVIDER_NAME);
        generator.initialize(ECNamedCurveTable.getParameterSpec("prime256v1"));
        KeyPair pair = generator.generateKeyPair();
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        return new String[]{b64.encodeToString(Utils.encode((ECPublicKey) pair.getPublic())),
                b64.encodeToString(Utils.encode((ECPrivateKey) pair.getPrivate()))};
    }

    // ------------------------------------------------------------------------------------
    // Checking what browsers send us
    // ------------------------------------------------------------------------------------

    /**
     * Only https URLs of known push services are accepted, because the server will later send
     * requests to this address.
     */
    public void checkEndpoint(String endpoint) {
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (IllegalArgumentException e) {
            throw invalid("This is not a valid push address");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean schemeOk = scheme.equals("https") || (properties.allowInsecureEndpoints() && scheme.equals("http"));
        boolean hostOk = properties.allowedHosts() != null && properties.allowedHosts().stream()
                .map(h -> h.toLowerCase(Locale.ROOT).trim())
                .anyMatch(h -> h.startsWith(".") ? host.endsWith(h) : host.equals(h));
        if (!schemeOk || !hostOk || uri.getUserInfo() != null) {
            throw invalid("Push messages can only be sent through a browser's own push service");
        }
    }

    /** The browser's public key is an uncompressed P-256 point (65 bytes); the auth secret is 16 bytes. */
    public void checkKeys(String p256dh, String auth) {
        byte[] key = decode(p256dh), secret = decode(auth);
        if (key == null || key.length != 65 || key[0] != 4 || secret == null || secret.length != 16) {
            throw invalid("The browser sent invalid push keys");
        }
    }

    private static byte[] decode(String value) {
        try {
            return value == null ? null : Base64.getUrlDecoder().decode(value.replace('+', '-').replace('/', '_').replace("=", ""));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static BusinessRuleException invalid(String message) {
        return new BusinessRuleException(HttpStatus.BAD_REQUEST, message);
    }

    // ------------------------------------------------------------------------------------
    // Sending
    // ------------------------------------------------------------------------------------

    /** Sends the alerts of a committed transaction, in the background. */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPushRequested(PushRequested event) {
        send(event.messages());
    }

    /** @return how many messages a push service accepted */
    public int send(List<PushMessage> messages) {
        if (!properties.enabled() || messages.isEmpty()) {
            return 0;
        }
        Map<Long, List<PushMessage>> byUser = messages.stream()
                .collect(Collectors.groupingBy(PushMessage::userId, LinkedHashMap::new, Collectors.toList()));
        int delivered = 0;
        for (PushSubscription subscription : repository.findByUserIdIn(byUser.keySet())) {
            for (PushMessage message : byUser.get(subscription.getUserId())) {
                if (deliver(subscription, message)) delivered++;
            }
        }
        return delivered;
    }

    /** Encrypts and posts one message, and keeps the subscription's health up to date. */
    boolean deliver(PushSubscription subscription, PushMessage message) {
        int status;
        try {
            byte[] payload = json.writeValueAsBytes(Map.of("title", message.title(), "body", message.body(),
                    "url", message.url(), "tag", message.tag()));
            HttpPost post = vapid.preparePost(new Notification(subscription.getEndpoint(), subscription.getP256dh(),
                    subscription.getAuth(), payload, TTL_SECONDS), Encoding.AES128GCM);
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(subscription.getEndpoint()))
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(EntityUtils.toByteArray(post.getEntity())));
            for (Header h : post.getAllHeaders()) {
                if (!RESTRICTED_HEADERS.contains(h.getName().toLowerCase(Locale.ROOT))) {
                    request.header(h.getName(), h.getValue());
                }
            }
            status = http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.warn("Push to subscription {} failed: {}", subscription.getId(), e.getMessage());
            status = -1;
        }

        if (status >= 200 && status < 300) {
            subscription.setLastSuccessAt(clock.instant());
            subscription.setFailures(0);
            repository.save(subscription);
            return true;
        }
        if (status == 404 || status == 410) {
            // The browser unsubscribed or the subscription expired.
            repository.delete(subscription);
        } else {
            subscription.setFailures(subscription.getFailures() + 1);
            if (subscription.getFailures() >= MAX_FAILURES) {
                repository.delete(subscription);
            } else {
                repository.save(subscription);
            }
            log.warn("Push service answered {} for subscription {}", status, subscription.getId());
        }
        return false;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** Bytes of a message as the browser will decrypt them; used in tests. */
    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}

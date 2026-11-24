package com.foodrescue.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foodrescue.config.PushProperties;
import com.foodrescue.domain.PushSubscription;
import com.foodrescue.exception.BusinessRuleException;
import com.foodrescue.repository.PushSubscriptionRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Web Push against a local fake push service. The message is decrypted here with an independent
 * implementation of RFC 8291 (plain JDK crypto), and the VAPID signature is checked, so the test
 * proves the messages are what a real browser can read.
 */
class WebPushSenderTest {

    private static final Instant NOW = Instant.parse("2026-11-17T15:00:00Z");
    private static final Base64.Decoder B64 = Base64.getUrlDecoder();
    private static final Base64.Encoder B64E = Base64.getUrlEncoder().withoutPadding();

    HttpServer server;
    final AtomicInteger status = new AtomicInteger(201);
    final Map<String, String> headers = new ConcurrentHashMap<>();
    volatile byte[] body;
    PushSubscriptionRepository repository;
    WebPushSender sender;
    String[] vapid;

    // The "browser": a P-256 key pair and a 16-byte auth secret.
    KeyPair browser;
    byte[] browserPublic;
    byte[] auth = new byte[16];

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/push", exchange -> {
            exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), v.get(0)));
            body = exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(status.get(), -1);
            exchange.close();
        });
        server.start();

        vapid = WebPushSender.generateKeys();
        repository = mock(PushSubscriptionRepository.class);
        sender = new WebPushSender(new PushProperties(true, vapid[0], vapid[1], "mailto:test@example.org",
                List.of("localhost"), true), repository, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));

        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        browser = g.generateKeyPair();
        browserPublic = raw((ECPublicKey) browser.getPublic());
        new java.security.SecureRandom().nextBytes(auth);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private PushSubscription subscription() {
        PushSubscription s = new PushSubscription();
        s.setId(1L);
        s.setUserId(7L);
        s.setEndpoint("http://localhost:" + server.getAddress().getPort() + "/push");
        s.setP256dh(B64E.encodeToString(browserPublic));
        s.setAuth(B64E.encodeToString(auth));
        return s;
    }

    private static final PushMessage MESSAGE = new PushMessage(7L, "Rescue Drop at Corner Bakery",
            "4 × Sourdough loaf for 4.50 lei · until 17:30", "/?alert=12", "alert-12");

    @Test
    void theBrowserCanDecryptTheMessageAndVerifyTheSender() throws Exception {
        PushSubscription s = subscription();

        assertThat(sender.deliver(s, MESSAGE)).isTrue();

        assertThat(headers.get("content-encoding")).isEqualTo("aes128gcm");
        assertThat(Integer.parseInt(headers.get("ttl"))).isEqualTo(WebPushSender.TTL_SECONDS);
        JsonNode payload = new ObjectMapper().readTree(decrypt(body));
        assertThat(payload.get("title").asText()).isEqualTo("Rescue Drop at Corner Bakery");
        assertThat(payload.get("body").asText()).isEqualTo("4 × Sourdough loaf for 4.50 lei · until 17:30");
        assertThat(payload.get("url").asText()).isEqualTo("/?alert=12");
        assertThat(payload.get("tag").asText()).isEqualTo("alert-12");

        // VAPID: "vapid t=<JWT>, k=<our public key>", the JWT signed with our private key.
        String authz = headers.get("authorization");
        assertThat(authz).startsWith("vapid t=");
        String jwt = authz.substring("vapid t=".length(), authz.indexOf(','));
        assertThat(authz.substring(authz.indexOf("k=") + 2).trim()).isEqualTo(vapid[0]);
        String[] parts = jwt.split("\\.");
        JsonNode claims = new ObjectMapper().readTree(B64.decode(parts[1]));
        // The library leaves the port out of the audience; real push services all use the default https port.
        assertThat(claims.get("aud").asText()).isEqualTo("http://localhost");
        assertThat(claims.get("exp").asLong()).isGreaterThan(Instant.now().getEpochSecond());
        assertThat(claims.get("sub").asText()).isEqualTo("mailto:test@example.org");
        Signature es256 = Signature.getInstance("SHA256withECDSAinP1363Format");
        es256.initVerify(publicKeyFrom(B64.decode(vapid[0]), (ECPublicKey) browser.getPublic()));
        es256.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(es256.verify(B64.decode(parts[2]))).isTrue();

        assertThat(s.getLastSuccessAt()).isEqualTo(NOW);
        verify(repository).save(s);
    }

    @Test
    void aSubscriptionThePushServiceNoLongerKnowsIsDeleted() {
        status.set(410);
        PushSubscription s = subscription();

        assertThat(sender.deliver(s, MESSAGE)).isFalse();

        verify(repository).delete(s);
    }

    @Test
    void repeatedFailuresEventuallyRemoveTheSubscription() {
        status.set(500);
        PushSubscription s = subscription();
        for (int i = 1; i < WebPushSender.MAX_FAILURES; i++) {
            sender.deliver(s, MESSAGE);
        }
        verify(repository, never()).delete(any());
        assertThat(s.getFailures()).isEqualTo(WebPushSender.MAX_FAILURES - 1);

        sender.deliver(s, MESSAGE);

        verify(repository).delete(s);
    }

    @Test
    void sendGoesToEveryBrowserOfTheUserAndNothingWhenDisabled() throws Exception {
        when(repository.findByUserIdIn(any())).thenReturn(List.of(subscription(), subscription()));
        assertThat(sender.send(List.of(MESSAGE))).isEqualTo(2);

        WebPushSender off = new WebPushSender(new PushProperties(false, vapid[0], vapid[1], "mailto:a@example.org",
                List.of("localhost"), true), repository, new ObjectMapper(), Clock.systemUTC());
        assertThat(off.send(List.of(MESSAGE))).isZero();
    }

    @Test
    void onlyHttpsAddressesOfKnownPushServicesAreAccepted() throws Exception {
        WebPushSender strict = new WebPushSender(new PushProperties(true, vapid[0], vapid[1], "mailto:a@example.org",
                List.of("fcm.googleapis.com", ".push.services.mozilla.com", ".push.apple.com"), false),
                repository, new ObjectMapper(), Clock.systemUTC());

        strict.checkEndpoint("https://fcm.googleapis.com/fcm/send/abc");
        strict.checkEndpoint("https://updates.push.services.mozilla.com/wpush/v2/abc");
        strict.checkEndpoint("https://web.push.apple.com/QGx");
        for (String bad : List.of("http://fcm.googleapis.com/fcm/send/abc", "https://fcm.googleapis.com.evil.example/x",
                "https://evilpush.apple.com/x", "https://169.254.169.254/latest", "https://user@fcm.googleapis.com/x",
                "not a url", "file:///etc/passwd")) {
            assertThatThrownBy(() -> strict.checkEndpoint(bad)).as(bad).isInstanceOf(BusinessRuleException.class);
        }
    }

    @Test
    void browserKeysMustHaveTheRightShape() {
        sender.checkKeys(B64E.encodeToString(browserPublic), B64E.encodeToString(auth));
        assertThatThrownBy(() -> sender.checkKeys("abc", B64E.encodeToString(auth))).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> sender.checkKeys(B64E.encodeToString(browserPublic), B64E.encodeToString(new byte[8])))
                .isInstanceOf(BusinessRuleException.class);
    }

    // ---------- RFC 8291 decryption, as the browser does it ----------

    private byte[] decrypt(byte[] message) throws Exception {
        ByteBuffer in = ByteBuffer.wrap(message);
        byte[] salt = new byte[16];
        in.get(salt);
        in.getInt(); // record size
        byte[] serverPublic = new byte[in.get() & 0xff];
        in.get(serverPublic);
        byte[] ciphertext = new byte[in.remaining()];
        in.get(ciphertext);

        KeyAgreement ecdh = KeyAgreement.getInstance("ECDH");
        ecdh.init(browser.getPrivate());
        ecdh.doPhase(publicKeyFrom(serverPublic, (ECPublicKey) browser.getPublic()), true);
        byte[] secret = ecdh.generateSecret();

        byte[] prkKey = hmac(auth, secret);
        byte[] ikm = hmac(prkKey, concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), browserPublic, serverPublic, new byte[]{1}));
        byte[] prk = hmac(salt, ikm);
        byte[] cek = Arrays.copyOf(hmac(prk, concat("Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), new byte[]{1})), 16);
        byte[] nonce = Arrays.copyOf(hmac(prk, concat("Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), new byte[]{1})), 12);

        Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] padded = aes.doFinal(ciphertext);
        int end = padded.length - 1;
        while (padded[end] == 0) end--;
        assertThat(padded[end]).as("last-record delimiter").isEqualTo((byte) 2);
        return Arrays.copyOf(padded, end);
    }

    private static byte[] hmac(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.writeBytes(p);
        return out.toByteArray();
    }

    private static byte[] raw(ECPublicKey key) {
        return concat(new byte[]{4}, fixed(key.getW().getAffineX()), fixed(key.getW().getAffineY()));
    }

    private static byte[] fixed(BigInteger n) {
        byte[] b = n.toByteArray();
        byte[] out = new byte[32];
        System.arraycopy(b, Math.max(0, b.length - 32), out, Math.max(0, 32 - b.length), Math.min(32, b.length));
        return out;
    }

    private static java.security.PublicKey publicKeyFrom(byte[] raw, ECPublicKey sameCurve) throws Exception {
        ECPoint point = new ECPoint(new BigInteger(1, Arrays.copyOfRange(raw, 1, 33)), new BigInteger(1, Arrays.copyOfRange(raw, 33, 65)));
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, sameCurve.getParams()));
    }
}

package com.foodrescue.service;

import com.foodrescue.config.AccountProperties;
import com.foodrescue.domain.Order;
import com.foodrescue.domain.OrderStatus;
import com.foodrescue.domain.PasswordResetToken;
import com.foodrescue.domain.Store;
import com.foodrescue.domain.User;
import com.foodrescue.domain.UserRole;
import com.foodrescue.domain.EligibilityStatus;
import com.foodrescue.dto.AccountUpdateRequest;
import com.foodrescue.dto.AuthResponse;
import com.foodrescue.dto.PasswordChangeRequest;
import com.foodrescue.dto.PasswordResetConfirmRequest;
import com.foodrescue.dto.UserResponse;
import com.foodrescue.exception.BusinessRuleException;
import com.foodrescue.exception.ResourceNotFoundException;
import com.foodrescue.repository.OrderRepository;
import com.foodrescue.repository.PasswordResetTokenRepository;
import com.foodrescue.repository.StoreRepository;
import com.foodrescue.repository.UserRepository;
import com.foodrescue.security.RateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One's own account: details, password, signing out other devices, downloading one's data and
 * deleting the account, plus "forgot password" by email.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccountService {

    static final Duration RESET_LINK_VALIDITY = Duration.ofHours(1);
    /** At most this many reset emails per account per hour, so nobody can flood someone's inbox. */
    static final int RESET_EMAILS_PER_HOUR = 3;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final StoreRepository storeRepository;
    private final PasswordResetTokenRepository resetTokenRepository;
    private final OrderService orderService;
    private final AuthService authService;
    private final PasswordEncoder passwordEncoder;
    private final MailService mailService;
    private final RateLimiter rateLimiter;
    private final AccountProperties properties;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    // ---------- details ----------

    @Transactional
    public UserResponse update(Long userId, AccountUpdateRequest request) {
        User user = load(userId);
        String email = AuthService.normalizeEmail(request.email());
        if (!email.equals(user.getEmail())) {
            checkPassword(user, request.currentPassword());
            if (userRepository.existsByEmail(email)) {
                throw new BusinessRuleException(HttpStatus.CONFLICT, "An account with this email already exists");
            }
            user.setEmail(email);
            log.info("User {} changed their email", userId);
        }
        user.setFullName(request.fullName().strip());
        user.setLanguage(request.language());
        return UserResponse.from(user);
    }

    /** Sets a new password and signs out every other device; this device gets a new login. */
    @Transactional
    public AuthResponse changePassword(Long userId, PasswordChangeRequest request) {
        User user = load(userId);
        checkPassword(user, request.currentPassword());
        setPassword(user, request.newPassword());
        log.info("User {} changed their password", userId);
        mailService.sendPasswordChanged(user.getEmail(), user.getFullName(), user.getLanguage());
        return authService.issueFor(user);
    }

    /** Ends the login on every device (a lost phone, a shared computer); this device gets a new login. */
    @Transactional
    public AuthResponse signOutEverywhere(Long userId) {
        User user = load(userId);
        user.setTokensValidAfter(clock.instant());
        log.info("User {} signed out everywhere", userId);
        return authService.issueFor(user);
    }

    // ---------- forgot password ----------

    public boolean resetAvailable() {
        return mailService.canSendResetLinks();
    }

    /**
     * Sends a reset link if the email has an account. The answer is the same either way, so
     * this cannot be used to find out who has an account.
     */
    @Transactional
    public void requestReset(String rawEmail, String clientIp) {
        rateLimiter.hit(RateLimiter.Limit.RESET_IP, clientIp);
        if (!resetAvailable()) {
            throw new BusinessRuleException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Password reset by email is not set up yet. Please contact us.");
        }
        Instant now = clock.instant();
        userRepository.findByEmail(AuthService.normalizeEmail(rawEmail))
                .filter(u -> u.getDeletedAt() == null && u.getBlockedAt() == null)
                .filter(u -> resetTokenRepository.countByUserIdAndCreatedAtAfter(u.getId(), now.minus(Duration.ofHours(1))) < RESET_EMAILS_PER_HOUR)
                .ifPresent(user -> {
                    byte[] bytes = new byte[32];
                    RANDOM.nextBytes(bytes);
                    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
                    PasswordResetToken row = new PasswordResetToken();
                    row.setUserId(user.getId());
                    row.setTokenHash(sha256(token));
                    row.setCreatedAt(now);
                    row.setExpiresAt(now.plus(RESET_LINK_VALIDITY));
                    resetTokenRepository.save(row);
                    // In the part after "#", so the token never reaches server or proxy logs.
                    mailService.sendPasswordReset(user.getEmail(), user.getFullName(), user.getLanguage(),
                            properties.publicUrl() + "/#reset/" + token);
                    log.info("User {} asked for a password reset link", user.getId());
                });
    }

    /** Sets the new password from a reset link and signs the person in. */
    @Transactional
    public AuthResponse confirmReset(PasswordResetConfirmRequest request) {
        Instant now = clock.instant();
        PasswordResetToken row = resetTokenRepository.findByTokenHash(sha256(request.token()))
                .filter(t -> t.getUsedAt() == null && t.getExpiresAt().isAfter(now))
                .orElseThrow(() -> new BusinessRuleException(HttpStatus.BAD_REQUEST,
                        "This reset link has expired or was already used. Please ask for a new one."));
        User user = load(row.getUserId());
        if (user.getDeletedAt() != null) {
            throw new BusinessRuleException(HttpStatus.BAD_REQUEST, "This reset link has expired or was already used. Please ask for a new one.");
        }
        setPassword(user, request.newPassword());
        resetTokenRepository.deleteByUserId(user.getId());
        rateLimiter.reset(RateLimiter.Limit.FAILED_LOGIN_EMAIL, user.getEmail());
        log.info("User {} reset their password", user.getId());
        return authService.issueFor(user);
    }

    @Scheduled(fixedDelay = 3_600_000)
    @Transactional
    public void removeOldResetLinks() {
        resetTokenRepository.deleteExpiredBefore(clock.instant().minus(Duration.ofDays(1)));
    }

    // ---------- your data ----------

    /** Everything kept about the person, as one JSON document (the GDPR right of access). */
    @Transactional(readOnly = true)
    public Map<String, Object> export(Long userId) {
        User user = load(userId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("exportedAt", clock.instant());
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("fullName", user.getFullName());
        account.put("email", user.getEmail());
        account.put("role", user.getRole());
        account.put("language", user.getLanguage());
        account.put("createdAt", user.getCreatedAt());
        account.put("termsAcceptedAt", user.getTermsAcceptedAt());
        account.put("freeItemsVerification", user.getEligibilityStatus());
        data.put("account", account);

        List<Map<String, Object>> orders = new ArrayList<>();
        for (Order o : orderRepository.findByUserIdOrderByCreatedAtDesc(userId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("item", o.getFoodItem().getName());
            row.put("shop", o.getFoodItem().getStore().getName());
            row.put("quantity", o.getQuantity());
            row.put("totalPrice", o.getTotalPrice());
            row.put("type", o.getType());
            row.put("status", o.getStatus());
            row.put("reservedAt", o.getCreatedAt());
            row.put("collectedAt", o.getPickedUpAt());
            orders.add(row);
        }
        data.put("orders", orders);
        data.put("favouriteShops", jdbc.queryForList(
                "select s.name, f.created_at from favourite_store f join store s on s.id = f.store_id where f.user_id = ?", userId));
        data.put("favouriteProducts", jdbc.queryForList(
                "select t.name, f.created_at from favourite_product f join product_template t on t.id = f.template_id where f.user_id = ?", userId));
        data.put("alertSettings", jdbc.queryForList("select * from radar_profile where user_id = ?", userId));
        data.put("rescueDropAlerts", jdbc.queryForList("select * from drop_alert_preference where user_id = ?", userId));
        data.put("notifications", jdbc.queryForList(
                "select title, body, created_at, read_at from notification where user_id = ? order by created_at desc", userId));
        data.put("pushDevices", jdbc.queryForObject("select count(*) from push_subscription where user_id = ?", Long.class, userId));
        List<Map<String, Object>> stores = new ArrayList<>();
        for (Store s : storeRepository.findByOwnerId(userId)) {
            stores.add(Map.of("name", s.getName(), "address", s.getAddress(), "active", s.isActive(), "createdAt", s.getCreatedAt()));
        }
        data.put("stores", stores);
        return data;
    }

    // ---------- deleting the account ----------

    /**
     * Deletes the account (the GDPR right to erasure). Personal data is removed at once; the
     * account row stays, emptied, so the shops' order history and the impact figures still add
     * up. Open reservations are cancelled and the food goes back on sale.
     */
    @Transactional
    public void delete(Long userId, String password) {
        User user = load(userId);
        checkPassword(user, password);
        if (user.getRole() == UserRole.ADMIN && userRepository.countByRoleAndDeletedAtIsNull(UserRole.ADMIN) <= 1) {
            throw new BusinessRuleException(HttpStatus.CONFLICT, "You are the only administrator. Add another administrator first.");
        }
        if (user.getRole() == UserRole.STORE_OWNER) {
            List<Store> stores = storeRepository.findByOwnerId(userId);
            for (Store s : stores) {
                if (!orderRepository.findByFoodItemStoreIdAndStatusOrderByPickupDeadlineAsc(s.getId(), OrderStatus.RESERVED).isEmpty()) {
                    throw new BusinessRuleException(HttpStatus.CONFLICT,
                            "Customers are still waiting to collect food at " + s.getName() + ". Hand it over or wait until their reservations end, then try again.");
                }
            }
            // Hidden from customers for good; nothing new can be reserved there.
            stores.forEach(s -> s.setActive(false));
        }
        for (Order o : orderRepository.findByUserIdOrderByCreatedAtDesc(userId)) {
            if (o.getStatus() == OrderStatus.RESERVED) orderService.cancel(userId, o.getId());
        }
        for (String table : List.of("favourite_store", "favourite_product", "radar_profile", "drop_alert_preference",
                "notification", "push_subscription", "password_reset_token")) {
            jdbc.update("delete from " + table + " where user_id = ?", userId);
        }
        Instant now = clock.instant();
        user.setFullName("Deleted account");
        user.setEmail("deleted-" + userId + "@deleted.invalid");
        user.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setEligibilityStatus(EligibilityStatus.NOT_REQUESTED);
        user.setDeletedAt(now);
        user.setTokensValidAfter(now);
        log.info("User {} deleted their account", userId);
    }

    // ---------- helpers ----------

    private void checkPassword(User user, String password) {
        String key = String.valueOf(user.getId());
        rateLimiter.check(RateLimiter.Limit.PASSWORD_CHECK_USER, key);
        if (password == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            rateLimiter.record(RateLimiter.Limit.PASSWORD_CHECK_USER, key);
            throw new BusinessRuleException(HttpStatus.FORBIDDEN, "Your current password is not right");
        }
    }

    private void setPassword(User user, String password) {
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setTokensValidAfter(clock.instant());
    }

    private User load(Long userId) {
        return userRepository.findById(userId)
                .filter(u -> u.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

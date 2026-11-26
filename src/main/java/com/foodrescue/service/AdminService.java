package com.foodrescue.service;

import com.foodrescue.domain.EligibilityStatus;
import com.foodrescue.domain.Store;
import com.foodrescue.domain.User;
import com.foodrescue.domain.UserRole;
import com.foodrescue.dto.AdminStoreResponse;
import com.foodrescue.dto.UserResponse;
import com.foodrescue.exception.BusinessRuleException;
import com.foodrescue.exception.ResourceNotFoundException;
import com.foodrescue.repository.StoreRepository;
import com.foodrescue.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Platform operator tasks: deciding who may receive free Pay-It-Forward items, and suspending
 * stores. Only ADMIN accounts reach these methods (see SecurityConfig).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminService {

    private final UserRepository userRepository;
    private final StoreRepository storeRepository;
    private final com.foodrescue.repository.OrderRepository orderRepository;
    private final OrderService orderService;
    private final NoShowService noShowService;
    private final java.time.Clock clock;

    /** People matching a search (name or email), at most 50, with their missed pickups. */
    @Transactional(readOnly = true)
    public List<com.foodrescue.dto.AdminUserResponse> listUsers(String query) {
        String q = query == null ? "" : query.strip().toLowerCase(java.util.Locale.ROOT);
        String pattern = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        List<User> users = userRepository.search(pattern, org.springframework.data.domain.PageRequest.of(0, 50));
        java.util.Map<Long, Integer> noShows = noShowService.counts(users.stream().map(User::getId).toList());
        return users.stream().map(u -> com.foodrescue.dto.AdminUserResponse.from(u, noShows.getOrDefault(u.getId(), 0))).toList();
    }

    /**
     * Blocks an account: it cannot sign in, its logins end at once, open reservations are
     * cancelled (the food goes back on sale) and a shop owner's stores are suspended.
     */
    @Transactional
    public com.foodrescue.dto.AdminUserResponse block(Long adminId, Long userId, String reason) {
        User user = userRepository.findById(userId).filter(u -> u.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        if (user.getId().equals(adminId)) {
            throw new BusinessRuleException(HttpStatus.BAD_REQUEST, "You cannot block your own account");
        }
        if (user.getRole() == UserRole.ADMIN) {
            throw new BusinessRuleException(HttpStatus.BAD_REQUEST, "Administrators cannot be blocked");
        }
        java.time.Instant now = clock.instant();
        user.setBlockedAt(now);
        user.setBlockedReason(reason == null || reason.isBlank() ? null : reason.strip());
        user.setTokensValidAfter(now);
        for (com.foodrescue.domain.Order o : orderRepository.findByUserIdOrderByCreatedAtDesc(userId)) {
            if (o.getStatus() == com.foodrescue.domain.OrderStatus.RESERVED) orderService.cancel(userId, o.getId());
        }
        if (user.getRole() == UserRole.STORE_OWNER) {
            storeRepository.findByOwnerId(userId).forEach(s -> s.setActive(false));
        }
        log.info("Admin {} blocked user {}", adminId, userId);
        return com.foodrescue.dto.AdminUserResponse.from(user, noShowService.counts(List.of(userId)).getOrDefault(userId, 0));
    }

    /** Lets the person sign in again. A shop owner's stores stay suspended until reactivated one by one. */
    @Transactional
    public com.foodrescue.dto.AdminUserResponse unblock(Long adminId, Long userId) {
        User user = userRepository.findById(userId).filter(u -> u.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        user.setBlockedAt(null);
        user.setBlockedReason(null);
        log.info("Admin {} unblocked user {}", adminId, userId);
        return com.foodrescue.dto.AdminUserResponse.from(user, noShowService.counts(List.of(userId)).getOrDefault(userId, 0));
    }

    @Transactional(readOnly = true)
    public List<UserResponse> listPendingEligibility() {
        return userRepository.findByEligibilityStatusOrderByCreatedAtAsc(EligibilityStatus.PENDING).stream()
                .map(UserResponse::from)
                .toList();
    }

    /**
     * Approves or rejects a consumer. Also works on an already decided account, so an
     * administrator can revoke an approval or correct a rejection.
     */
    @Transactional
    public UserResponse decideEligibility(Long adminId, Long userId, boolean approved) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        if (user.getRole() != UserRole.CONSUMER) {
            throw new BusinessRuleException(HttpStatus.BAD_REQUEST, "Only consumer accounts can be verified");
        }
        user.setEligibilityStatus(approved ? EligibilityStatus.APPROVED : EligibilityStatus.REJECTED);
        log.info("Admin {} {} user {} for Pay-It-Forward", adminId, approved ? "approved" : "rejected", userId);
        return UserResponse.from(user);
    }

    @Transactional(readOnly = true)
    public List<AdminStoreResponse> listStores() {
        return storeRepository.findAllByOrderByNameAsc().stream()
                .map(AdminStoreResponse::from)
                .toList();
    }

    /**
     * A suspended store disappears from search and cannot take new reservations. Reservations
     * made before stay valid, so customers can still collect what they were promised.
     */
    @Transactional
    public AdminStoreResponse setStoreActive(Long adminId, Long storeId, boolean active) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new ResourceNotFoundException("Store", storeId));
        store.setActive(active);
        log.info("Admin {} {} store {}", adminId, active ? "reactivated" : "suspended", storeId);
        return AdminStoreResponse.from(store);
    }
}

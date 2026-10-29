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

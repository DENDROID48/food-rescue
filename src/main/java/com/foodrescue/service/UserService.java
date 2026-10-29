package com.foodrescue.service;

import com.foodrescue.domain.EligibilityStatus;
import com.foodrescue.domain.User;
import com.foodrescue.domain.UserRole;
import com.foodrescue.dto.AuthResponse;
import com.foodrescue.dto.RegisterUserRequest;
import com.foodrescue.dto.UserResponse;
import com.foodrescue.exception.BusinessRuleException;
import com.foodrescue.exception.ResourceNotFoundException;
import com.foodrescue.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Account registration, lookup and the request for free-item eligibility. */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthService authService;

    /** Creates the account and signs the new user in straight away. */
    @Transactional
    public AuthResponse register(RegisterUserRequest request) {
        if (request.role() == UserRole.ADMIN) {
            throw new BusinessRuleException(HttpStatus.BAD_REQUEST, "role must be CONSUMER or STORE_OWNER");
        }

        String email = AuthService.normalizeEmail(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new BusinessRuleException(HttpStatus.CONFLICT, "An account with this email already exists");
        }

        User user = new User();
        user.setFullName(request.fullName().trim());
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(request.role());

        User saved = userRepository.save(user);
        log.info("Registered user {} as {}", saved.getId(), saved.getRole());
        return authService.issueFor(saved);
    }

    @Transactional(readOnly = true)
    public UserResponse get(Long userId) {
        return UserResponse.from(loadUser(userId));
    }

    /**
     * A consumer asks to be verified for free Pay-It-Forward items. An administrator then
     * approves or rejects the request (see AdminService). After a rejection the consumer may ask again.
     */
    @Transactional
    public UserResponse requestEligibility(Long userId) {
        User user = loadUser(userId);
        switch (user.getEligibilityStatus()) {
            case APPROVED -> throw new BusinessRuleException(HttpStatus.CONFLICT, "This account is already verified");
            case PENDING -> { /* already waiting, nothing to do */ }
            case NOT_REQUESTED, REJECTED -> {
                user.setEligibilityStatus(EligibilityStatus.PENDING);
                log.info("User {} requested Pay-It-Forward verification", userId);
            }
        }
        return UserResponse.from(user);
    }

    private User loadUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
    }
}

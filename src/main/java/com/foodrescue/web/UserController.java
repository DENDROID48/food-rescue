package com.foodrescue.web;

import com.foodrescue.dto.AuthResponse;
import com.foodrescue.dto.RegisterUserRequest;
import com.foodrescue.dto.UserResponse;
import com.foodrescue.security.CurrentUser;
import com.foodrescue.service.UserService;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Account endpoints. */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * POST /api/v1/users   body: {"fullName": "...", "email": "...", "password": "...", "role": "CONSUMER"}
     *
     * <p>Creates the account and returns a login token, so the user is signed in right away.</p>
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterUserRequest request, HttpServletRequest http) {
        return userService.register(request, http.getRemoteAddr());
    }

    /** GET /api/v1/users/me   returns the account of the caller. */
    @GetMapping("/me")
    public UserResponse me(@CurrentUser Long userId) {
        return userService.get(userId);
    }

    /** POST /api/v1/users/me/eligibility-request   asks an administrator to verify the caller for free items. */
    @PostMapping("/me/eligibility-request")
    public UserResponse requestEligibility(@CurrentUser Long userId) {
        return userService.requestEligibility(userId);
    }
}

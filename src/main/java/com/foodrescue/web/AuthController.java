package com.foodrescue.web;

import com.foodrescue.dto.PasswordResetConfirmRequest;
import com.foodrescue.dto.PasswordResetRequest;
import com.foodrescue.service.AccountService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import com.foodrescue.dto.AuthResponse;
import com.foodrescue.dto.LoginRequest;
import com.foodrescue.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AccountService accountService;

    /**
     * POST /api/v1/auth/login   body: {"email": "...", "password": "..."}
     *
     * <p>Returns a token to send as {@code Authorization: Bearer <token>} on later requests.
     * Logging out is done by the client simply forgetting the token.</p>
     */
    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return authService.login(request, http.getRemoteAddr());
    }

    /**
     * POST /api/v1/auth/password-reset   body: {"email": "..."}
     *
     * <p>Emails a reset link if the address has an account. Always answers 202, so it cannot be
     * used to find out who has an account.</p>
     */
    @PostMapping("/password-reset")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void requestReset(@Valid @RequestBody PasswordResetRequest request, HttpServletRequest http) {
        accountService.requestReset(request.email(), http.getRemoteAddr());
    }

    /** POST /api/v1/auth/password-reset/confirm   body: {"token": "...", "newPassword": "..."}   signs the person in. */
    @PostMapping("/password-reset/confirm")
    public AuthResponse confirmReset(@Valid @RequestBody PasswordResetConfirmRequest request) {
        return accountService.confirmReset(request);
    }
}

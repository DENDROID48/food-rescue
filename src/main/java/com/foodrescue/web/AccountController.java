package com.foodrescue.web;

import com.foodrescue.dto.AccountUpdateRequest;
import com.foodrescue.dto.AuthResponse;
import com.foodrescue.dto.PasswordChangeRequest;
import com.foodrescue.dto.PasswordCheckRequest;
import com.foodrescue.dto.UserResponse;
import com.foodrescue.security.CurrentUser;
import com.foodrescue.service.AccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** One's own account, for every role. */
@RestController
@RequestMapping("/api/v1/users/me")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    /** PUT /api/v1/users/me   body: {"fullName", "email", "language", "currentPassword" (needed to change the email)}. */
    @PutMapping
    public UserResponse update(@CurrentUser Long userId, @Valid @RequestBody AccountUpdateRequest request) {
        return accountService.update(userId, request);
    }

    /** PUT /api/v1/users/me/password   signs out other devices and returns a new login for this one. */
    @PutMapping("/password")
    public AuthResponse changePassword(@CurrentUser Long userId, @Valid @RequestBody PasswordChangeRequest request) {
        return accountService.changePassword(userId, request);
    }

    /** POST /api/v1/users/me/sign-out-everywhere   ends every login; returns a new one for this device. */
    @PostMapping("/sign-out-everywhere")
    public AuthResponse signOutEverywhere(@CurrentUser Long userId) {
        return accountService.signOutEverywhere(userId);
    }

    /** GET /api/v1/users/me/export   everything kept about the caller, as a JSON file. */
    @GetMapping("/export")
    public ResponseEntity<Map<String, Object>> export(@CurrentUser Long userId) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"food-rescue-my-data.json\"")
                .body(accountService.export(userId));
    }

    /** POST /api/v1/users/me/deletion   body: {"password"}   deletes the account. Cannot be undone. */
    @PostMapping("/deletion")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUser Long userId, @Valid @RequestBody PasswordCheckRequest request) {
        accountService.delete(userId, request.password());
    }
}

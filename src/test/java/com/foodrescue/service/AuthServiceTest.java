package com.foodrescue.service;

import com.foodrescue.dto.LoginRequest;
import com.foodrescue.exception.BusinessRuleException;
import com.foodrescue.repository.UserRepository;
import com.foodrescue.security.TokenService;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AuthServiceTest {

    @Test
    void constructorPreparesADummyHashSoUnknownEmailsTakeAsLongAsWrongPasswords() {
        UserRepository users = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode(anyString())).thenReturn("$2a$dummy");
        when(users.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        AuthService auth = new AuthService(users, encoder, mock(TokenService.class));
        verify(encoder).encode(anyString());   // done once, in the constructor

        assertThatThrownBy(() -> auth.login(new LoginRequest("Nobody@Example.com", "secret123")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("Wrong email or password");
        // The password was still checked, against the dummy hash.
        verify(encoder).matches(eq("secret123"), eq("$2a$dummy"));
    }
}

package com.foodrescue.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SecurityConfigTest {

    @Test
    void constructorKeepsTheResolverThatSuppliesTheLoggedInUser() {
        CurrentUserArgumentResolver resolver = mock(CurrentUserArgumentResolver.class);
        SecurityConfig config = new SecurityConfig(resolver, new ObjectMapper());

        List<HandlerMethodArgumentResolver> resolvers = new ArrayList<>();
        config.addArgumentResolvers(resolvers);

        assertThat(resolvers).containsExactly(resolver);
    }

    @Test
    void passwordsAreHashedWithBcrypt() {
        var encoder = new SecurityConfig(mock(CurrentUserArgumentResolver.class), new ObjectMapper()).passwordEncoder();
        String hash = encoder.encode("demo1234");
        assertThat(hash).startsWith("$2").isNotEqualTo("demo1234");
        assertThat(encoder.matches("demo1234", hash)).isTrue();
    }
}

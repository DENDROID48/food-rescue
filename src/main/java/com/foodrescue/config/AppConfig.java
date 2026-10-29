package com.foodrescue.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class AppConfig {

    /**
     * Injecting a {@link Clock} instead of calling {@code Instant.now()} directly lets unit
     * tests freeze time, which makes expiry logic easy to test.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}

package com.foodrescue.web;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * GET /api/v1/health   for the hosting platform or a load balancer: 200 while the app can reach
 * its database, 503 otherwise. Public, and says nothing else about the installation.
 */
@RestController
@RequiredArgsConstructor
public class HealthController {

    private final JdbcTemplate jdbc;

    @GetMapping("/api/v1/health")
    public ResponseEntity<Map<String, String>> health() {
        try {
            jdbc.queryForObject("select 1", Integer.class);
            return ResponseEntity.ok(Map.of("status", "UP"));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("status", "DOWN"));
        }
    }
}

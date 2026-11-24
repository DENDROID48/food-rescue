package com.foodrescue.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Web Push settings, from the {@code foodrescue.push} section of application.yml.
 *
 * @param enabled                 false stops all sending (subscriptions are still accepted)
 * @param publicKey               VAPID public key, base64url; generated at startup when empty
 * @param privateKey              VAPID private key, base64url; keep it secret
 * @param subject                 contact for push services: "mailto:..." or an https URL
 * @param allowedHosts            push service hosts the server may send to; ".example.com" also
 *                                matches its subdomains. This stops the server from being made to
 *                                send requests to arbitrary addresses.
 * @param allowInsecureEndpoints  allow http endpoints (tests only)
 */
@ConfigurationProperties(prefix = "foodrescue.push")
public record PushProperties(
        boolean enabled,
        String publicKey,
        String privateKey,
        String subject,
        List<String> allowedHosts,
        boolean allowInsecureEndpoints) {
}

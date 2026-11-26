package com.foodrescue.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Accounts: password reset emails and limits on repeated attempts, read from
 * {@code foodrescue.accounts} in application.yml.
 *
 * @param publicUrl        the address people open the app at, used in emailed links (no trailing slash)
 * @param mailFrom         sender of emails, e.g. "Food Rescue &lt;hello@example.org&gt;"; emails are off while empty
 * @param logResetLinks    write reset links to the log instead of needing a mail server; for development only
 * @param rateLimits       limit repeated sign-in, sign-up and reset attempts (turned off in tests only)
 */
@ConfigurationProperties(prefix = "foodrescue.accounts")
public record AccountProperties(String publicUrl, String mailFrom, boolean logResetLinks, boolean rateLimits) {

    public AccountProperties {
        publicUrl = publicUrl == null || publicUrl.isBlank() ? "http://localhost:8080" : publicUrl.strip().replaceAll("/+$", "");
        mailFrom = mailFrom == null ? "" : mailFrom.strip();
    }
}

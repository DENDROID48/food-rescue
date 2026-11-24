package com.foodrescue.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where uploaded photos are kept on disk, read from {@code foodrescue.uploads} in application.yml.
 *
 * @param dir folder for the photo files; relative paths start in the folder the program runs from
 */
@ConfigurationProperties(prefix = "foodrescue.uploads")
public record UploadProperties(String dir) {

    public UploadProperties {
        if (dir == null || dir.isBlank()) {
            dir = "uploads";
        }
    }
}

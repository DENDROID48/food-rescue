package com.foodrescue.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Where the data lives, read from the {@code foodrescue.storage} section of application.yml or
 * from program arguments ({@code --mode=files --data-dir=...}, see {@code LaunchOptions}).
 * Invalid values stop the program at startup with a message naming the setting.
 *
 * @param mode            MEMORY: nothing is written to disk; FILES: CSV files in {@code dataDir}
 * @param dataDir         folder of the data files, relative to where the program is started
 * @param autosaveSeconds how often changes are written to the files (they are also written on shutdown)
 */
@ConfigurationProperties(prefix = "foodrescue.storage")
@Validated
public record StorageProperties(
        @NotNull StorageMode mode,
        @NotBlank String dataDir,
        @Min(1) @Max(3600) int autosaveSeconds) {

    public enum StorageMode {
        MEMORY,
        FILES
    }
}

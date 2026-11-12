package com.foodrescue.storage;

import com.foodrescue.FoodRescueApplication;
import com.foodrescue.domain.FoodItem;
import com.foodrescue.domain.User;
import com.foodrescue.exception.DataFileException;
import com.foodrescue.repository.FoodItemRepository;
import com.foodrescue.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Starts the whole application twice on the same data folder, each time with a new empty
 * in-memory database, to prove that what the first run saved is what the second run reads.
 */
class FileDataStoreTest {

    @TempDir
    Path dataDir;

    private ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(FoodRescueApplication.class).run(
                "--foodrescue.storage.mode=files",
                "--foodrescue.storage.data-dir=" + dataDir,
                "--foodrescue.geocoding.enabled=false",
                "--server.port=0",
                // A fresh database for every start, so only the files carry data across.
                "--spring.datasource.url=jdbc:h2:mem:" + UUID.randomUUID()
                        + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH");
    }

    @Test
    void dataSavedByOneRunIsReadBackByTheNext() throws Exception {
        long itemId;
        try (ConfigurableApplicationContext first = start()) {
            // First start: the folder is empty, so the demo data is created and saved.
            FoodItemRepository items = first.getBean(FoodItemRepository.class);
            FoodItem item = items.findAll().get(0);
            item.setName("Renamed, with \"quotes\"");
            items.save(item);
            itemId = item.getId();
        } // Closing the application saves the change.

        assertThat(dataDir.resolve(FileDataStore.USERS)).exists();
        assertThat(Files.readString(dataDir.resolve(FileDataStore.USERS))).contains("owner@example.com");

        try (ConfigurableApplicationContext second = start()) {
            UserRepository users = second.getBean(UserRepository.class);
            assertThat(users.count()).isEqualTo(5);
            assertThat(users.findByEmail("owner@example.com")).isPresent();
            assertThat(second.getBean(FoodItemRepository.class).findById(itemId))
                    .get().extracting(FoodItem::getName).isEqualTo("Renamed, with \"quotes\"");

            // New rows continue after the loaded ids instead of colliding with them.
            User extra = new User();
            extra.setFullName("New Person");
            extra.setEmail("new@example.com");
            extra.setPasswordHash("x".repeat(60));
            assertThat(users.save(extra).getId()).isGreaterThan(5);
        }
    }

    @Test
    void aBadValueStopsTheStartAndNamesTheFileAndLine() throws Exception {
        Files.writeString(dataDir.resolve(FileDataStore.USERS), String.join(",", FileDataStore.USER_COLUMNS) + "\n"
                + "1,Ana,ana@example.com,hash,KING,GENERAL,NOT_REQUESTED,2026-01-01T00:00:00Z\n");

        Throwable failure = catchThrowable(this::start);
        while (failure != null && !(failure instanceof DataFileException)) {
            failure = failure.getCause();
        }
        assertThat(failure)
                .isInstanceOf(DataFileException.class)
                .hasMessageContaining("users.csv, line 2")
                .hasMessageContaining("'role'")
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }
}

package com.foodrescue.storage;

import com.foodrescue.config.UploadProperties;
import com.foodrescue.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Photos are written to files in the uploads folder and read back from them. */
class FilePhotoStorageTest {

    @TempDir
    Path uploads;

    private FilePhotoStorage storage() {
        return new FilePhotoStorage(new UploadProperties(uploads.toString()));
    }

    @Test
    void constructorCreatesOneFolderPerKindOfPhoto() {
        storage();
        assertThat(uploads.resolve("food-items")).isDirectory();
        assertThat(uploads.resolve("templates")).isDirectory();
    }

    @Test
    void uploadPropertiesFallBackToTheUploadsFolder() {
        assertThat(new UploadProperties(" ").dir()).isEqualTo("uploads");
        assertThat(new UploadProperties("/data/photos").dir()).isEqualTo("/data/photos");
    }

    @Test
    void savedPhotoIsAFileAndReadsBackTheSame() throws Exception {
        FilePhotoStorage storage = storage();
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};

        storage.save(PhotoFolder.FOOD_ITEMS, 42, png);

        assertThat(Files.readAllBytes(uploads.resolve("food-items/42"))).isEqualTo(png);
        assertThat(storage.load(PhotoFolder.FOOD_ITEMS, 42)).isEqualTo(png);
    }

    @Test
    void savingAgainReplacesThePhoto() {
        FilePhotoStorage storage = storage();
        storage.save(PhotoFolder.TEMPLATES, 7, new byte[]{1});
        storage.save(PhotoFolder.TEMPLATES, 7, new byte[]{2, 2});
        assertThat(storage.load(PhotoFolder.TEMPLATES, 7)).containsExactly(2, 2);
    }

    @Test
    void copyGivesTheOfferItsOwnFile() throws Exception {
        FilePhotoStorage storage = storage();
        storage.save(PhotoFolder.TEMPLATES, 7, new byte[]{9, 9});

        assertThat(storage.copy(PhotoFolder.TEMPLATES, 7, PhotoFolder.FOOD_ITEMS, 100)).isTrue();
        storage.delete(PhotoFolder.TEMPLATES, 7);

        assertThat(storage.load(PhotoFolder.FOOD_ITEMS, 100)).containsExactly(9, 9);
        assertThat(storage.copy(PhotoFolder.TEMPLATES, 8, PhotoFolder.FOOD_ITEMS, 101)).isFalse();
    }

    @Test
    void missingOrDeletedPhotoIsReportedAsNotFound() {
        FilePhotoStorage storage = storage();
        storage.save(PhotoFolder.FOOD_ITEMS, 5, new byte[]{1});
        storage.delete(PhotoFolder.FOOD_ITEMS, 5);

        assertThat(uploads.resolve("food-items/5")).doesNotExist();
        assertThatThrownBy(() -> storage.load(PhotoFolder.FOOD_ITEMS, 5))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Photo with id 5 was not found");
    }
}

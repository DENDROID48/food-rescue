package com.foodrescue.storage;

import com.foodrescue.config.UploadProperties;
import com.foodrescue.exception.BusinessRuleException;
import com.foodrescue.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Keeps photos as files in the uploads folder (application.yml: {@code foodrescue.uploads.dir}):
 * {@code uploads/food-items/42} is the photo of offer 42, {@code uploads/templates/7} the photo of
 * saved product 7.
 */
@Component
@Slf4j
public class FilePhotoStorage implements PhotoStorage {

    private final Path root;

    public FilePhotoStorage(UploadProperties properties) {
        this.root = Path.of(properties.dir()).toAbsolutePath().normalize();
        try {
            for (PhotoFolder folder : PhotoFolder.values()) {
                Files.createDirectories(root.resolve(folder.folderName()));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create the uploads folder " + root, e);
        }
        log.info("Photos are stored in {}", root);
    }

    @Override
    public void save(PhotoFolder folder, long id, byte[] data) {
        Path target = file(folder, id);
        try {
            // Written to a temporary file first and then renamed, so a reader never sees half a photo.
            Path temp = Files.createTempFile(target.getParent(), id + "-", ".part");
            Files.write(temp, data);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw failed("saved", target, e);
        }
    }

    @Override
    public byte[] load(PhotoFolder folder, long id) {
        Path source = file(folder, id);
        try {
            return Files.readAllBytes(source);
        } catch (NoSuchFileException e) {
            throw new ResourceNotFoundException("Photo", id);
        } catch (IOException e) {
            throw failed("read", source, e);
        }
    }

    @Override
    public boolean copy(PhotoFolder fromFolder, long fromId, PhotoFolder toFolder, long toId) {
        try {
            save(toFolder, toId, load(fromFolder, fromId));
            return true;
        } catch (ResourceNotFoundException missing) {
            log.warn("Photo file {} is missing; the copy goes without a photo", file(fromFolder, fromId));
            return false;
        }
    }

    /**
     * Inside a database transaction the file is removed only after the transaction commits, so a
     * change that is rolled back never loses its photo.
     */
    @Override
    public void delete(PhotoFolder folder, long id) {
        Path target = file(folder, id);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteNow(target);
                }
            });
        } else {
            deleteNow(target);
        }
    }

    private static void deleteNow(Path target) {
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            log.warn("Could not delete photo file {}: {}", target, e.getMessage());
        }
    }

    /** Ids are numbers, so a file name can never point outside the uploads folder. */
    Path file(PhotoFolder folder, long id) {
        return root.resolve(folder.folderName()).resolve(Long.toString(id));
    }

    private static BusinessRuleException failed(String done, Path path, IOException e) {
        log.error("Photo file {} could not be {}", path, done, e);
        return new BusinessRuleException(HttpStatus.INTERNAL_SERVER_ERROR, "The photo could not be " + done + ", please try again");
    }
}

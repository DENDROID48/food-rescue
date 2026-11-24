package com.foodrescue.service;

import com.foodrescue.domain.FoodItem;
import com.foodrescue.domain.FoodItemPhoto;
import com.foodrescue.exception.BusinessRuleException;
import com.foodrescue.exception.ResourceNotFoundException;
import com.foodrescue.repository.FoodItemPhotoRepository;
import com.foodrescue.storage.PhotoFile;
import com.foodrescue.storage.PhotoFolder;
import com.foodrescue.storage.PhotoStorage;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;

/**
 * Photos of food items. Store owners upload and remove them; anyone can view them.
 *
 * <p>The image type is decided from the file's first bytes, not from the name or the type the
 * browser claims, so a file pretending to be an image is refused.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FoodItemPhotoService {

    static final int MAX_BYTES = 2 * 1024 * 1024;

    private final FoodItemPhotoRepository photoRepository;
    private final StoreService storeService;
    private final EntityManager entityManager;
    private final PhotoStorage photoStorage;

    @Transactional
    public void upload(Long ownerId, Long storeId, Long itemId, byte[] data) {
        storeService.requireOwnedStore(ownerId, storeId);
        String contentType = checkImage(data);

        FoodItem item = storeService.loadItemOfStoreForUpdate(storeId, itemId);
        FoodItemPhoto photo = photoRepository.findById(itemId).orElseGet(FoodItemPhoto::new);
        photo.setFoodItemId(itemId);
        photo.setContentType(contentType);
        photoRepository.save(photo);
        photoStorage.save(PhotoFolder.FOOD_ITEMS, itemId, data);

        item.setHasPhoto(true);
        // Raise the item's version even when only the photo bytes changed (hasPhoto was already
        // true): the version is part of the photo link, so browsers then fetch the new photo.
        entityManager.lock(item, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        log.info("Store {} uploaded a photo for item {}", storeId, itemId);
    }

    @Transactional
    public void delete(Long ownerId, Long storeId, Long itemId) {
        storeService.requireOwnedStore(ownerId, storeId);
        FoodItem item = storeService.loadItemOfStoreForUpdate(storeId, itemId);
        photoRepository.deleteById(itemId);
        photoStorage.delete(PhotoFolder.FOOD_ITEMS, itemId);
        item.setHasPhoto(false);
        log.info("Store {} removed the photo of item {}", storeId, itemId);
    }

    @Transactional(readOnly = true)
    public PhotoFile get(Long itemId) {
        FoodItemPhoto photo = photoRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Photo of food item", itemId));
        return new PhotoFile(photo.getContentType(), photoStorage.load(PhotoFolder.FOOD_ITEMS, itemId));
    }

    /** Checks size and real image type; returns the content type. Also used for product photos. */
    static String checkImage(byte[] data) {
        if (data.length == 0) {
            throw new BusinessRuleException(HttpStatus.BAD_REQUEST, "The file is empty");
        }
        if (data.length > MAX_BYTES) {
            throw new BusinessRuleException(HttpStatus.PAYLOAD_TOO_LARGE, "Photos may be at most 2 MB");
        }
        String contentType = detectImageType(data);
        if (contentType == null) {
            throw new BusinessRuleException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Upload a JPEG, PNG or WebP image");
        }
        return contentType;
    }

    /** JPEG, PNG or WebP from the file signature, or null for anything else. */
    static String detectImageType(byte[] data) {
        if (startsWith(data, 0xFF, 0xD8, 0xFF)) {
            return "image/jpeg";
        }
        if (startsWith(data, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return "image/png";
        }
        if (data.length >= 12 && startsWith(data, 'R', 'I', 'F', 'F')
                && Arrays.equals(Arrays.copyOfRange(data, 8, 12), new byte[]{'W', 'E', 'B', 'P'})) {
            return "image/webp";
        }
        return null;
    }

    private static boolean startsWith(byte[] data, int... prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((data[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}

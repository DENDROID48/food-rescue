package com.foodrescue.service;

import com.foodrescue.domain.ProductTemplate;
import com.foodrescue.domain.ProductTemplatePhoto;
import com.foodrescue.domain.Store;
import com.foodrescue.dto.TemplateRequest;
import com.foodrescue.dto.TemplateResponse;
import com.foodrescue.exception.BusinessRuleException;
import com.foodrescue.exception.ResourceNotFoundException;
import com.foodrescue.repository.ProductTemplatePhotoRepository;
import com.foodrescue.repository.ProductTemplateRepository;
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

import java.util.List;

/**
 * Reusable products of a store. They hold everything that stays the same from day to day and
 * no quantity; {@link DailyOfferService} turns them into today's offers.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProductTemplateService {

    private final ProductTemplateRepository templateRepository;
    private final ProductTemplatePhotoRepository photoRepository;
    private final StoreService storeService;
    private final EntityManager entityManager;
    private final PhotoStorage photoStorage;

    @Transactional(readOnly = true)
    public List<TemplateResponse> list(Long ownerId, Long storeId) {
        storeService.requireOwnedStore(ownerId, storeId);
        return templateRepository.findByStoreIdAndArchivedFalseOrderByNameAsc(storeId).stream()
                .map(TemplateResponse::from)
                .toList();
    }

    @Transactional
    public TemplateResponse create(Long ownerId, Long storeId, TemplateRequest request) {
        Store store = storeService.requireOwnedStore(ownerId, storeId);
        validate(request);
        ProductTemplate template = new ProductTemplate();
        template.setStore(store);
        apply(template, request);
        ProductTemplate saved = templateRepository.save(template);
        log.info("Store {} created product template {}", storeId, saved.getId());
        return TemplateResponse.from(saved);
    }

    /** Changes the template only; offers already created from it keep their own copy of the details. */
    @Transactional
    public TemplateResponse update(Long ownerId, Long storeId, Long templateId, TemplateRequest request) {
        storeService.requireOwnedStore(ownerId, storeId);
        validate(request);
        ProductTemplate template = loadOwnedForUpdate(storeId, templateId);
        apply(template, request);
        log.info("Store {} updated product template {}", storeId, templateId);
        return TemplateResponse.from(template);
    }

    /** Hides the template from the dashboard. Past and current offers are not affected. */
    @Transactional
    public void archive(Long ownerId, Long storeId, Long templateId) {
        storeService.requireOwnedStore(ownerId, storeId);
        loadOwnedForUpdate(storeId, templateId).setArchived(true);
        log.info("Store {} archived product template {}", storeId, templateId);
    }

    @Transactional
    public void uploadPhoto(Long ownerId, Long storeId, Long templateId, byte[] data) {
        storeService.requireOwnedStore(ownerId, storeId);
        String contentType = FoodItemPhotoService.checkImage(data);
        ProductTemplate template = loadOwnedForUpdate(storeId, templateId);
        ProductTemplatePhoto photo = photoRepository.findById(templateId).orElseGet(ProductTemplatePhoto::new);
        photo.setTemplateId(templateId);
        photo.setContentType(contentType);
        photoRepository.save(photo);
        photoStorage.save(PhotoFolder.TEMPLATES, templateId, data);
        template.setHasPhoto(true);
        // A new version changes the photo link, so browsers load the new photo.
        entityManager.lock(template, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
    }

    @Transactional
    public void deletePhoto(Long ownerId, Long storeId, Long templateId) {
        storeService.requireOwnedStore(ownerId, storeId);
        ProductTemplate template = loadOwnedForUpdate(storeId, templateId);
        photoRepository.deleteById(templateId);
        photoStorage.delete(PhotoFolder.TEMPLATES, templateId);
        template.setHasPhoto(false);
    }

    @Transactional(readOnly = true)
    public PhotoFile getPhoto(Long templateId) {
        ProductTemplatePhoto photo = photoRepository.findById(templateId)
                .orElseThrow(() -> new ResourceNotFoundException("Photo of product", templateId));
        return new PhotoFile(photo.getContentType(), photoStorage.load(PhotoFolder.TEMPLATES, templateId));
    }

    /** Loads and locks a template of the store; templates of other stores are reported as missing. */
    ProductTemplate loadOwnedForUpdate(Long storeId, Long templateId) {
        ProductTemplate template = templateRepository.findByIdForUpdate(templateId)
                .orElseThrow(() -> new ResourceNotFoundException("Product", templateId));
        if (!template.getStore().getId().equals(storeId)) {
            throw new ResourceNotFoundException("Product", templateId);
        }
        return template;
    }

    private void validate(TemplateRequest request) {
        if (request.discountedPrice().compareTo(request.originalPrice()) >= 0) {
            throw new BusinessRuleException(HttpStatus.BAD_REQUEST, "discountedPrice must be lower than originalPrice");
        }
        if (!request.pickupEnd().isAfter(request.pickupStart())) {
            throw new BusinessRuleException(HttpStatus.BAD_REQUEST, "The pickup window must end after it starts, on the same day");
        }
    }

    private void apply(ProductTemplate template, TemplateRequest request) {
        template.setName(request.name().trim());
        template.setDescription(request.description() == null || request.description().isBlank() ? null : request.description().trim());
        template.setKind(request.kind());
        template.setOriginalPrice(request.originalPrice());
        template.setDiscountedPrice(request.discountedPrice());
        template.setPickupStart(request.pickupStart());
        template.setPickupEnd(request.pickupEnd());
        template.setUnitWeightGrams(request.unitWeightGrams());
        template.setTags(request.tags() == null || request.tags().isEmpty()
                ? java.util.EnumSet.noneOf(com.foodrescue.domain.FoodTag.class) : java.util.EnumSet.copyOf(request.tags()));
    }
}

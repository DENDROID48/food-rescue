package com.foodrescue.web;

import com.foodrescue.storage.PhotoFile;
import com.foodrescue.service.ProductTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * GET /api/v1/template-photos/{id}. Public like food photos, because an {@code <img>} tag cannot
 * send the login token. The link contains the template version, so its content never changes.
 */
@RestController
@RequiredArgsConstructor
public class TemplatePhotoController {

    private final ProductTemplateService templateService;

    @GetMapping("/api/v1/template-photos/{id}")
    public ResponseEntity<byte[]> photo(@PathVariable Long id) {
        PhotoFile photo = templateService.getPhoto(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(photo.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
                .body(photo.data());
    }
}

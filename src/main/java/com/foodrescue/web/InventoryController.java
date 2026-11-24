package com.foodrescue.web;

import com.foodrescue.dto.AutopilotPlan;
import com.foodrescue.dto.BundleDropRequest;
import com.foodrescue.dto.DropResponse;
import com.foodrescue.dto.FoodItemResponse;
import com.foodrescue.dto.LaunchDropRequest;
import com.foodrescue.dto.OfferStatusRequest;
import com.foodrescue.dto.OfferUpdateRequest;
import com.foodrescue.dto.PublishOffersRequest;
import com.foodrescue.dto.ReminderRequest;
import com.foodrescue.dto.TemplateRequest;
import com.foodrescue.dto.TemplateResponse;
import com.foodrescue.dto.TodayResponse;
import com.foodrescue.security.CurrentUser;
import com.foodrescue.service.AnalyticsService;
import com.foodrescue.service.AutopilotService;
import com.foodrescue.dto.StoreInsights;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import com.foodrescue.service.DailyOfferService;
import com.foodrescue.service.RescueDropService;
import com.foodrescue.service.ProductTemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Daily inventory of a store: reusable products (templates), today's offers made from them, the
 * day's figures and the in-app reminder. Only the owner of the store may call these
 * (SecurityConfig limits /stores/** to store owners; the services check ownership).
 */
@RestController
@Validated
@RequestMapping("/api/v1/stores/{storeId}")
@RequiredArgsConstructor
public class InventoryController {

    private final ProductTemplateService templateService;
    private final DailyOfferService dailyOfferService;
    private final AutopilotService autopilotService;
    private final RescueDropService rescueDropService;
    private final AnalyticsService analyticsService;

    // ---------- reusable products ----------

    /** GET /api/v1/stores/{storeId}/templates */
    @GetMapping("/templates")
    public List<TemplateResponse> templates(@CurrentUser Long userId, @PathVariable Long storeId) {
        return templateService.list(userId, storeId);
    }

    /** POST /api/v1/stores/{storeId}/templates   body: TemplateRequest (pickup times like "18:00") */
    @PostMapping("/templates")
    @ResponseStatus(HttpStatus.CREATED)
    public TemplateResponse createTemplate(@CurrentUser Long userId, @PathVariable Long storeId,
                                           @Valid @RequestBody TemplateRequest request) {
        return templateService.create(userId, storeId, request);
    }

    @PutMapping("/templates/{templateId}")
    public TemplateResponse updateTemplate(@CurrentUser Long userId, @PathVariable Long storeId,
                                           @PathVariable Long templateId, @Valid @RequestBody TemplateRequest request) {
        return templateService.update(userId, storeId, templateId, request);
    }

    /** DELETE archives the product; offers made from it are kept. */
    @DeleteMapping("/templates/{templateId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archiveTemplate(@CurrentUser Long userId, @PathVariable Long storeId, @PathVariable Long templateId) {
        templateService.archive(userId, storeId, templateId);
    }

    /** Multipart form field "file": JPEG, PNG or WebP, at most 2 MB. */
    @PostMapping(path = "/templates/{templateId}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void uploadTemplatePhoto(@CurrentUser Long userId, @PathVariable Long storeId, @PathVariable Long templateId,
                                    @RequestParam("file") MultipartFile file) throws IOException {
        templateService.uploadPhoto(userId, storeId, templateId, file.getBytes());
    }

    @DeleteMapping("/templates/{templateId}/photo")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTemplatePhoto(@CurrentUser Long userId, @PathVariable Long storeId, @PathVariable Long templateId) {
        templateService.deletePhoto(userId, storeId, templateId);
    }

    // ---------- today ----------

    /** GET /api/v1/stores/{storeId}/today   offers, products, figures and reminder for the store's current day. */
    @GetMapping("/today")
    public TodayResponse today(@CurrentUser Long userId, @PathVariable Long storeId) {
        return dailyOfferService.today(userId, storeId);
    }

    /** POST /api/v1/stores/{storeId}/offers   creates today's offers from products, all or nothing. */
    @PostMapping("/offers")
    @ResponseStatus(HttpStatus.CREATED)
    public List<FoodItemResponse> publish(@CurrentUser Long userId, @PathVariable Long storeId,
                                          @Valid @RequestBody PublishOffersRequest request) {
        return dailyOfferService.publish(userId, storeId, request);
    }

    /** PATCH /api/v1/stores/{storeId}/offers/{itemId}   quick change of quantities and pickup window. */
    @PatchMapping("/offers/{itemId}")
    public FoodItemResponse updateOffer(@CurrentUser Long userId, @PathVariable Long storeId, @PathVariable Long itemId,
                                        @Valid @RequestBody OfferUpdateRequest request) {
        return dailyOfferService.update(userId, storeId, itemId, request);
    }

    /** PUT /api/v1/stores/{storeId}/offers/{itemId}/status   body: {"status": "ACTIVE" | "PAUSED"} */
    @PutMapping("/offers/{itemId}/status")
    public FoodItemResponse setOfferStatus(@CurrentUser Long userId, @PathVariable Long storeId, @PathVariable Long itemId,
                                           @Valid @RequestBody OfferStatusRequest request) {
        return dailyOfferService.setStatus(userId, storeId, itemId, request.status());
    }

    // ---------- Store Autopilot ----------

    /** GET /api/v1/stores/{storeId}/autopilot   suggested quantities, prices and windows for today. Publishes nothing. */
    @GetMapping("/autopilot")
    public AutopilotPlan autopilot(@CurrentUser Long userId, @PathVariable Long storeId) {
        return autopilotService.plan(userId, storeId);
    }

    // ---------- Smart Analytics ----------

    /** GET /api/v1/stores/{storeId}/insights?days=28   results, forecast and recommendations (7 to 90 days). */
    @GetMapping("/insights")
    public StoreInsights insights(@CurrentUser Long userId, @PathVariable Long storeId,
                                  @RequestParam(defaultValue = "28") @Min(7) @Max(90) int days) {
        return analyticsService.insights(userId, storeId, days);
    }

    /** GET /api/v1/stores/{storeId}/insights/export.csv?days=28   one line per ended offer. */
    @GetMapping(path = "/insights/export.csv", produces = "text/csv")
    public void exportCsv(@CurrentUser Long userId, @PathVariable Long storeId,
                          @RequestParam(defaultValue = "28") @Min(7) @Max(90) int days,
                          jakarta.servlet.http.HttpServletResponse response) throws IOException {
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"food-rescue-" + storeId + "-" + days + "-days.csv\"");
        analyticsService.exportCsv(userId, storeId, days, response.getWriter());
    }

    // ---------- Rescue Drops ----------

    /** GET /api/v1/stores/{storeId}/drops/audience   how many opted-in customers a drop here would reach. */
    @GetMapping("/drops/audience")
    public Map<String, Integer> dropAudience(@CurrentUser Long userId, @PathVariable Long storeId) {
        return Map.of("customers", rescueDropService.audience(userId, storeId));
    }

    /** POST /api/v1/stores/{storeId}/drops   launches a drop of a saved product, starting now. */
    @PostMapping("/drops")
    @ResponseStatus(HttpStatus.CREATED)
    public DropResponse launchDrop(@CurrentUser Long userId, @PathVariable Long storeId,
                                   @Valid @RequestBody LaunchDropRequest request) {
        return rescueDropService.launch(userId, storeId, request);
    }

    /** POST /api/v1/stores/{storeId}/drops/bundle   turns leftovers of today's offers into surprise bags. */
    @PostMapping("/drops/bundle")
    @ResponseStatus(HttpStatus.CREATED)
    public DropResponse launchBundle(@CurrentUser Long userId, @PathVariable Long storeId,
                                     @Valid @RequestBody BundleDropRequest request) {
        return rescueDropService.bundle(userId, storeId, request);
    }

    // ---------- reminder ----------

    /** PUT /api/v1/stores/{storeId}/reminder   body: {"enabled": true, "time": "15:00"} */
    @PutMapping("/reminder")
    public TodayResponse.Reminder setReminder(@CurrentUser Long userId, @PathVariable Long storeId,
                                              @RequestBody ReminderRequest request) {
        return dailyOfferService.setReminder(userId, storeId, request);
    }

    /** POST /api/v1/stores/{storeId}/reminder/dismiss   hides today's reminder. */
    @PostMapping("/reminder/dismiss")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void dismissReminder(@CurrentUser Long userId, @PathVariable Long storeId) {
        dailyOfferService.dismissReminder(userId, storeId);
    }
}

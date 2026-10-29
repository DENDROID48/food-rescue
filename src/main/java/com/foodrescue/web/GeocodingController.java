package com.foodrescue.web;

import com.foodrescue.dto.PlaceResponse;
import com.foodrescue.exception.BusinessRuleException;
import com.foodrescue.service.GeocodingService;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Address lookup for the app, so users search by place name instead of coordinates. */
@RestController
@RequestMapping("/api/v1/geocode")
@RequiredArgsConstructor
@Validated
public class GeocodingController {

    private final GeocodingService geocodingService;

    /** GET /api/v1/geocode?q=Piata Victoriei, Timisoara   up to 5 matching places, best first. */
    @GetMapping
    public List<PlaceResponse> search(@RequestParam @Size(min = 3, max = 200) String q) {
        return geocodingService.search(q);
    }

    /** GET /api/v1/geocode/reverse?lat=45.75&lng=21.22   the nearest address to a point. */
    @GetMapping("/reverse")
    public PlaceResponse reverse(@RequestParam @DecimalMin("-90") @DecimalMax("90") double lat,
                                 @RequestParam @DecimalMin("-180") @DecimalMax("180") double lng) {
        return geocodingService.reverse(lat, lng)
                .orElseThrow(() -> new BusinessRuleException(HttpStatus.NOT_FOUND, "No address found near this point"));
    }
}

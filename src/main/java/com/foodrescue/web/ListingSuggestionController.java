package com.foodrescue.web;

import com.foodrescue.dto.ListingSuggestion;
import com.foodrescue.dto.ListingSuggestionRequest;
import com.foodrescue.service.ListingSuggestionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** AI-assisted listings: a suggestion from what the browser's image model saw in a photo. */
@RestController
@RequiredArgsConstructor
public class ListingSuggestionController {

    private final ListingSuggestionService listingSuggestionService;

    /** POST /api/v1/stores/listing-suggestions   store owners only; saves nothing. */
    @PostMapping("/api/v1/stores/listing-suggestions")
    public ListingSuggestion suggest(@Valid @RequestBody ListingSuggestionRequest request) {
        return listingSuggestionService.suggest(request);
    }
}

package com.pathwayai.backend.university.controller;

import com.pathwayai.backend.common.response.PageResponse;
import com.pathwayai.backend.university.dto.UniversityDetailResponse;
import com.pathwayai.backend.university.dto.UniversitySummaryResponse;
import com.pathwayai.backend.university.service.UniversityService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/universities")
public class UniversityController {

    private final UniversityService universityService;

    public UniversityController(UniversityService universityService) {
        this.universityService = universityService;
    }

    @GetMapping
    public PageResponse<UniversitySummaryResponse> list(
            @RequestParam(required = false) @Size(max = 100, message = "must be at most 100 characters") String q,
            @RequestParam(required = false)
            @Pattern(regexp = "[A-Za-z]{2}", message = "must be a 2-letter ISO country code") String country,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "must be >= 0") int page,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "must be between 1 and 100")
            @Max(value = 100, message = "must be between 1 and 100") int size,
            @RequestParam(defaultValue = "name")
            @Pattern(regexp = "name|city", message = "must be one of: name, city") String sort,
            @RequestParam(defaultValue = "asc")
            @Pattern(regexp = "(?i)asc|desc", message = "must be asc or desc") String direction) {

        Sort order = Sort.by(Sort.Direction.fromString(direction), sort).and(Sort.by("id"));
        return universityService.search(q, country, PageRequest.of(page, size, order));
    }

    @GetMapping("/{slug}")
    public UniversityDetailResponse get(
            @PathVariable @Size(max = 120, message = "must be at most 120 characters")
            @Pattern(regexp = "[a-z0-9]+(-[a-z0-9]+)*", message = "must be a lowercase slug") String slug) {
        return universityService.getBySlug(slug);
    }
}

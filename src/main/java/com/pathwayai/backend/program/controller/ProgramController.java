package com.pathwayai.backend.program.controller;

import com.pathwayai.backend.common.response.PageResponse;
import com.pathwayai.backend.program.dto.ProgramDetailResponse;
import com.pathwayai.backend.program.dto.ProgramListItemResponse;
import com.pathwayai.backend.program.entity.DegreeLevel;
import com.pathwayai.backend.program.service.ProgramSearchCriteria;
import com.pathwayai.backend.program.service.ProgramService;
import com.pathwayai.backend.tuition.entity.StudentCategory;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only program discovery. Program-first: each result also names the university offering it. */
@RestController
@RequestMapping("/api/v1/programs")
public class ProgramController {

    private static final String SLUG = "[a-z0-9]+(-[a-z0-9]+)*";
    /** Whitelisted public sort keys mapped to entity paths. */
    private static final Map<String, String> SORTS =
            Map.of("name", "name", "university", "university.name", "level", "degreeLevel");

    private final ProgramService programService;

    public ProgramController(ProgramService programService) {
        this.programService = programService;
    }

    @GetMapping
    public PageResponse<ProgramListItemResponse> list(
            @RequestParam(required = false) @Size(max = 100, message = "must be at most 100 characters") String q,
            @RequestParam(required = false)
            @Size(max = 120, message = "must be at most 120 characters")
            @Pattern(regexp = SLUG, message = "must be a lowercase slug") String field,
            @RequestParam(required = false)
            @Pattern(regexp = "(?i)bachelor|master|doctorate|diploma|certificate",
                    message = "must be one of: bachelor, master, doctorate, diploma, certificate") String level,
            @RequestParam(required = false)
            @Pattern(regexp = "[A-Za-z]{2}", message = "must be a 2-letter ISO country code") String country,
            @RequestParam(required = false)
            @Size(max = 120, message = "must be at most 120 characters")
            @Pattern(regexp = SLUG, message = "must be a lowercase slug") String university,
            @RequestParam(required = false)
            @Pattern(regexp = "[A-Za-z]{2,3}", message = "must be a 2 or 3-letter language code") String language,
            @RequestParam(required = false)
            @DecimalMin(value = "0", message = "must be >= 0") BigDecimal tuitionMax,
            @RequestParam(required = false)
            @Pattern(regexp = "[A-Za-z]{3}", message = "must be a 3-letter ISO currency code") String tuitionCurrency,
            @RequestParam(required = false)
            @Pattern(regexp = "(?i)domestic|international",
                    message = "must be one of: domestic, international") String tuitionCategory,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "must be >= 0") int page,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "must be between 1 and 100")
            @Max(value = 100, message = "must be between 1 and 100") int size,
            @RequestParam(defaultValue = "name")
            @Pattern(regexp = "name|university|level", message = "must be one of: name, university, level") String sort,
            @RequestParam(defaultValue = "asc")
            @Pattern(regexp = "(?i)asc|desc", message = "must be asc or desc") String direction) {

        var criteria = new ProgramSearchCriteria(
                q, field,
                level == null ? null : DegreeLevel.valueOf(level.toUpperCase(Locale.ROOT)),
                country, university, language, tuitionMax, tuitionCurrency,
                tuitionCategory == null ? null : StudentCategory.valueOf(tuitionCategory.toUpperCase(Locale.ROOT)));
        Sort order = Sort.by(Sort.Direction.fromString(direction), SORTS.get(sort)).and(Sort.by("name", "id"));
        return programService.search(criteria, PageRequest.of(page, size, order));
    }

    /** {@code identifier} is {@code <university-slug>--<program-slug>}, globally unique and stable. */
    @GetMapping("/{identifier}")
    public ProgramDetailResponse get(
            @PathVariable
            @Size(max = 245, message = "must be at most 245 characters")
            @Pattern(regexp = SLUG + "--" + SLUG,
                    message = "must look like <university-slug>--<program-slug>") String identifier) {
        return programService.getByIdentifier(identifier);
    }
}

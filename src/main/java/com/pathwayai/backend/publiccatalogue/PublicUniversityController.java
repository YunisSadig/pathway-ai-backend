package com.pathwayai.backend.publiccatalogue;

import com.pathwayai.backend.common.exception.ResourceNotFoundException;
import com.pathwayai.backend.common.response.PageResponse;
import com.pathwayai.backend.program.service.ProgramSearchCriteria;
import com.pathwayai.backend.program.service.ProgramService;
import com.pathwayai.backend.university.dto.UniversitySummaryResponse;
import com.pathwayai.backend.university.service.UniversityService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * PUBLIC, read-only university discovery for visitors who are not signed in.
 *
 * <p>Only an explicit allow-list of fields leaves through here (see {@link PublicUniversity}): the institution's name, city, country,
 * its OFFICIAL website address, our own verification date and a count of the programs we keep current. Everything else stays behind the
 * catalogue gate: program names, degree levels, fields of study, durations, languages, tuition, deadlines, requirements and descriptions,
 * because the reuse terms of the Studyinfo data are not stated in writing (docs/data/public-catalogue-reuse.md). The institution list obeys
 * the same listing rules as the protected catalogue, so an unverified or withheld institution never appears.
 */
@RestController
@RequestMapping("/api/v1/public/universities")
class PublicUniversityController {

    /** What a visitor may see about one institution. Adding a field here is a deliberate, reviewed act (a test pins the exact field set). */
    record PublicUniversity(String slug, String name, String city, String countryCode, String countryName, String websiteUrl,
                            long trackedPrograms, LocalDate verifiedOn) {
    }

    private final UniversityService universities;
    private final ProgramService programs;
    private final JdbcClient jdbc;

    PublicUniversityController(UniversityService universities, ProgramService programs, JdbcClient jdbc) {
        this.universities = universities;
        this.programs = programs;
        this.jdbc = jdbc;
    }

    @GetMapping
    PageResponse<PublicUniversity> list(
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) @Pattern(regexp = "[A-Za-z]{2}") String country,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "12") @Min(1) @Max(50) int size) {
        var found = universities.search(q, country, PageRequest.of(page, size, Sort.by("name").and(Sort.by("id"))));
        return new PageResponse<>(found.content().stream().map(this::enrich).toList(), found.number(), found.size(),
                found.totalElements(), found.totalPages());
    }

    @GetMapping("/{slug}")
    PublicUniversity get(@PathVariable @Size(max = 120) @Pattern(regexp = "[a-z0-9]+(-[a-z0-9]+)*") String slug) {
        return universities.findListedBySlug(slug).map(this::enrich).orElseThrow(() -> new ResourceNotFoundException("University", slug));
    }

    private PublicUniversity enrich(UniversitySummaryResponse u) {
        long tracked = programs.search(new ProgramSearchCriteria(null, null, null, null, u.slug(), null, null, null, null),
                PageRequest.of(0, 1)).totalElements();
        LocalDate verified = jdbc.sql("select verified_on from university where slug = :s").param("s", u.slug())
                .query((rs, n) -> rs.getObject(1, LocalDate.class)).optional().orElse(null);
        return new PublicUniversity(u.slug(), u.name(), u.city(), u.countryCode(), u.countryName(), u.websiteUrl(), tracked, verified);
    }
}

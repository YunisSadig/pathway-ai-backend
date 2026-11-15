package com.pathwayai.backend.publiccatalogue;

import com.pathwayai.backend.common.response.PageResponse;
import com.pathwayai.backend.program.dto.ProgramDetailResponse;
import com.pathwayai.backend.program.dto.ProgramListItemResponse;
import com.pathwayai.backend.program.entity.DegreeLevel;
import com.pathwayai.backend.program.service.ProgramSearchCriteria;
import com.pathwayai.backend.program.service.ProgramService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Locale;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * PUBLIC, read-only program discovery for anyone, signed in or not.
 *
 * <p>What leaves through here is the PUBLISHED OFFERING that the official Konfo external API exists to display on other sites: the
 * program's name, degree level, field, study mode, planned duration, teaching languages, campus city, the offering university, the
 * official program and admissions pages, and our own verification date. Tuition, deadlines and intakes, admission requirements,
 * source records, synchronization internals and the application link are NOT included: they stay behind the catalogue gate until
 * the provider's reuse terms are confirmed in writing (docs/data/public-catalogue-reuse.md). The list obeys the same listing rules as
 * the protected catalogue, so an unverified or withheld program never appears. Tuition filters are not offered here.
 */
@RestController
@RequestMapping("/api/v1/public/programs")
class PublicProgramController {

    private static final String SLUG = "[a-z0-9]+(-[a-z0-9]+)*";
    private static final java.util.Map<String, String> SORTS =
            java.util.Map.of("name", "name", "university", "university.name", "level", "degreeLevel");

    private final ProgramService programs;

    PublicProgramController(ProgramService programs) {
        this.programs = programs;
    }

    @GetMapping
    PageResponse<ProgramListItemResponse> list(
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) @Size(max = 120) @Pattern(regexp = SLUG) String field,
            @RequestParam(required = false) @Pattern(regexp = "(?i)bachelor|master|doctorate|diploma|certificate") String level,
            @RequestParam(required = false) @Pattern(regexp = "[A-Za-z]{2}") String country,
            @RequestParam(required = false) @Size(max = 120) @Pattern(regexp = SLUG) String university,
            @RequestParam(required = false) @Pattern(regexp = "[A-Za-z]{2,3}") String language,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "name") @Pattern(regexp = "name|university|level") String sort,
            @RequestParam(defaultValue = "asc") @Pattern(regexp = "(?i)asc|desc") String direction) {
        var criteria = new ProgramSearchCriteria(q, field,
                level == null ? null : DegreeLevel.valueOf(level.toUpperCase(Locale.ROOT)),
                country, university, language, null, null, null);
        Sort order = Sort.by(Sort.Direction.fromString(direction), SORTS.get(sort)).and(Sort.by("name", "id"));
        var found = programs.search(criteria, PageRequest.of(page, size, order));
        return new PageResponse<>(found.content().stream().map(PublicProgramController::open).toList(), found.number(), found.size(),
                found.totalElements(), found.totalPages());
    }

    /** {@code identifier} is {@code <university-slug>--<program-slug>}. Same field set as the list. */
    @GetMapping("/{identifier}")
    ProgramListItemResponse get(@PathVariable @Size(max = 245) @Pattern(regexp = SLUG + "--" + SLUG) String identifier) {
        ProgramDetailResponse d = programs.getByIdentifier(identifier);
        return open(new ProgramListItemResponse(d.identifier(), d.slug(), d.name(), d.degreeLevel(),
                d.field(), d.studyMode(), d.durationMonths(), d.teachingLanguages(), d.campusCity(), d.university(),
                d.officialProgramUrl(), d.officialAdmissionsUrl(), d.officialApplicationUrl(), d.verification()));
    }

    /** The application link is not part of the public tier. */
    private static ProgramListItemResponse open(ProgramListItemResponse p) {
        return new ProgramListItemResponse(p.identifier(), p.slug(), p.name(), p.degreeLevel(), p.field(), p.studyMode(),
                p.durationMonths(), p.teachingLanguages(), p.campusCity(), p.university(), p.officialProgramUrl(),
                p.officialAdmissionsUrl(), null, p.verification());
    }
}

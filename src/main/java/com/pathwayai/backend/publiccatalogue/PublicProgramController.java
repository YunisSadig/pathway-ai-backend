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

    /**
     * One program: the list fields plus {@code lastCheckedOn}, the day the official source was last read successfully. That is a
     * source CHECK, distinct from {@code verification.verifiedOn} (when the record was last verified) and from any actual change of a fact.
     */
    record PublicProgram(String identifier, String slug, String name, com.pathwayai.backend.program.entity.DegreeLevel degreeLevel,
                         ProgramListItemResponse.FieldRef field, com.pathwayai.backend.program.entity.StudyMode studyMode,
                         Integer durationMonths, java.util.List<String> teachingLanguages, String campusCity,
                         ProgramListItemResponse.UniversityRef university, String officialProgramUrl, String officialAdmissionsUrl,
                         String officialApplicationUrl, ProgramListItemResponse.Verification verification, java.time.LocalDate lastCheckedOn,
                         String catalogMembership) {
    }

    /** {@code identifier} is {@code <university-slug>--<program-slug>}. */
    @GetMapping("/{identifier}")
    PublicProgram get(@PathVariable @Size(max = 245) @Pattern(regexp = SLUG + "--" + SLUG) String identifier) {
        ProgramDetailResponse d = programs.getByIdentifier(identifier);
        var sync = d.synchronization();
        java.time.LocalDate checked = sync == null || sync.lastSuccessfulSyncAt() == null ? null
                : sync.lastSuccessfulSyncAt().atZone(java.time.ZoneOffset.UTC).toLocalDate();
        return new PublicProgram(d.identifier(), d.slug(), d.name(), d.degreeLevel(), d.field(), d.studyMode(), d.durationMonths(),
                d.teachingLanguages(), d.campusCity(), d.university(), d.officialProgramUrl(), d.officialAdmissionsUrl(), null,
                d.verification(), checked,d.catalogMembership());
    }

    /** The application link is not part of the public tier. */
    private static ProgramListItemResponse open(ProgramListItemResponse p) {
        return new ProgramListItemResponse(p.identifier(), p.slug(), p.name(), p.degreeLevel(), p.field(), p.studyMode(),
                p.durationMonths(), p.teachingLanguages(), p.campusCity(), p.university(), p.officialProgramUrl(),
                p.officialAdmissionsUrl(), null, p.verification());
    }
}

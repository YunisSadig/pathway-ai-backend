package com.pathwayai.backend.program.importer;

import static org.assertj.core.api.Assertions.assertThat;

import com.pathwayai.backend.program.entity.DegreeLevel;
import com.pathwayai.backend.program.entity.VerificationStatus;
import com.pathwayai.backend.source.entity.SourceType;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReferenceDataValidatorTest {

    private static final String SRC = "https://example.invalid/s";

    private static ReferenceData.ProgramData program(String slug, ReferenceData.VerificationData v, String applyUrl,
                                                     List<String> sources) {
        return new ReferenceData.ProgramData("u1", slug, "Name", DegreeLevel.BACHELOR, null, null, null, null,
                null, List.of(), null, applyUrl, v, sources, List.of(), List.of(), List.of());
    }

    private static ReferenceData file(ReferenceData.ProgramData... programs) {
        return new ReferenceData(1,
                List.of(new ReferenceData.CountryData("XA", "Synthetic")),
                List.of(),
                List.of(new ReferenceData.SourceData(SRC, "t", null, SourceType.OTHER, LocalDate.of(2026, 1, 1))),
                List.of(new ReferenceData.UniversityData("u1", "XA", "Uni", null, null, null)),
                List.of(programs));
    }

    private static final ReferenceData.VerificationData VERIFIED =
            new ReferenceData.VerificationData(VerificationStatus.VERIFIED, LocalDate.of(2026, 1, 1));

    @Test
    void aConsistentMinimalFilePasses() {
        assertThat(ReferenceDataValidator.validate(file(program("p1", null, null, List.of())))).isEmpty();
        assertThat(ReferenceDataValidator.validate(file(program("p1", VERIFIED, SRC, List.of(SRC))))).isEmpty();
    }

    @Test
    void verifiedNeedsADateAndASource() {
        var noDate = new ReferenceData.VerificationData(VerificationStatus.VERIFIED, null);
        assertThat(ReferenceDataValidator.validate(file(program("p1", noDate, null, List.of(SRC)))))
                .anyMatch(e -> e.contains("VERIFIED requires verifiedOn"));
        assertThat(ReferenceDataValidator.validate(file(program("p1", VERIFIED, null, List.of()))))
                .anyMatch(e -> e.contains("VERIFIED requires at least one source"));
    }

    @Test
    void anApplicationUrlRequiresAVerifiedProgram() {
        assertThat(ReferenceDataValidator.validate(file(program("p1", null, SRC, List.of()))))
                .anyMatch(e -> e.contains("application URL requires a VERIFIED program"));
    }

    @Test
    void catchesDanglingReferencesBadSlugsAndDuplicates() {
        var dangling = program("Bad Slug", VERIFIED, null, List.of("https://nowhere.invalid"));
        var errors = ReferenceDataValidator.validate(file(dangling, program("p2", null, null, List.of()),
                program("p2", null, null, List.of())));
        assertThat(errors).anyMatch(e -> e.contains("slug invalid"))
                .anyMatch(e -> e.contains("unknown source"))
                .anyMatch(e -> e.contains("duplicate program"));
    }

    @Test
    void rejectsAnUnsupportedSchemaVersion() {
        var f = new ReferenceData(99, List.of(), List.of(), List.of(), List.of(), List.of());
        assertThat(ReferenceDataValidator.validate(f)).anyMatch(e -> e.contains("schemaVersion"));
    }
}

package com.pathwayai.backend.program.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pathwayai.backend.TestcontainersConfiguration;
import com.pathwayai.backend.program.entity.DegreeProgram;
import com.pathwayai.backend.program.entity.VerificationStatus;
import com.pathwayai.backend.program.repository.DegreeProgramRepository;
import com.pathwayai.backend.tuition.entity.StudentCategory;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** The importer against PostgreSQL with a synthetic file. @Transactional rolls everything back per test. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ReferenceDataImporterIT {

    @Autowired ReferenceDataImporter importer;
    @Autowired DegreeProgramRepository programs;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    private static InputStream synthetic() throws IOException {
        return new ClassPathResource("reference-data/synthetic.json").getInputStream();
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    @Test
    void firstImportCreatesEverythingTheFileDescribes() throws IOException {
        var report = importer.importFrom(synthetic());

        assertThat(report.created()).containsEntry("countries", 1).containsEntry("fields", 2)
                .containsEntry("sources", 2).containsEntry("universities", 1).containsEntry("programs", 2)
                .containsEntry("intakes", 2).containsEntry("tuition", 1).containsEntry("requirements", 1);
        entityManager.flush();
        entityManager.clear();

        DegreeProgram full = programs.findByUniversitySlugAndSlug("synthetic-import-university", "full-program")
                .orElseThrow();
        assertThat(full.getVerificationStatus()).isEqualTo(VerificationStatus.VERIFIED);
        assertThat(full.getOfficialApplicationUrl()).isEqualTo("https://example.invalid/import/apply");
        assertThat(full.getTeachingLanguageCodes()).containsExactlyInAnyOrder("en", "de");
        assertThat(full.getAcademicField().getParent().getSlug()).isEqualTo("synthetic-science");
        assertThat(full.getSources()).hasSize(2);

        // A sparse record imports fine: unknown stays unknown, and it is not marked verified.
        DegreeProgram sparse = programs.findByUniversitySlugAndSlug("synthetic-import-university", "sparse-program")
                .orElseThrow();
        assertThat(sparse.getVerificationStatus()).isEqualTo(VerificationStatus.UNVERIFIED);
        assertThat(sparse.getAcademicField()).isNull();
        assertThat(sparse.getOfficialApplicationUrl()).isNull();
        assertThat(sparse.getDurationMonths()).isNull();
        assertThat(sparse.getTeachingLanguageCodes()).isEmpty();
    }

    @Test
    void importingTheSameFileAgainChangesNothing() throws IOException {
        importer.importFrom(synthetic());
        entityManager.flush();
        int[] before = {count("country"), count("academic_field"), count("source"), count("university"),
                count("degree_program"), count("program_intake"), count("tuition_fee"),
                count("academic_requirement"), count("program_language"), count("program_source")};

        var second = importer.importFrom(synthetic());
        entityManager.flush();
        var third = importer.importFrom(synthetic());
        entityManager.flush();

        assertThat(second.totalCreated()).isZero();
        assertThat(third.totalCreated()).isZero();
        int[] after = {count("country"), count("academic_field"), count("source"), count("university"),
                count("degree_program"), count("program_intake"), count("tuition_fee"),
                count("academic_requirement"), count("program_language"), count("program_source")};
        assertThat(after).containsExactly(before);
    }

    @Test
    void aCorrectedFileUpdatesInPlaceWithoutDuplicates() throws IOException {
        importer.importFrom(synthetic());
        entityManager.flush();
        ReferenceData data = ReferenceDataImporter.parse(synthetic());
        var changed = new ReferenceData.ProgramData("synthetic-import-university", "full-program",
                "Synthetic Full Program (renamed)", data.programs().get(0).degreeLevel(), "synthetic-science",
                null, null, 18, "English", java.util.List.of("en"), data.programs().get(0).officialProgramUrl(),
                null, new ReferenceData.VerificationData(VerificationStatus.UNVERIFIED, null),
                data.programs().get(0).sources(), data.programs().get(0).intakes(), java.util.List.of(),
                java.util.List.of());
        var corrected = new ReferenceData(1, data.countries(), data.fields(), data.sources(), data.universities(),
                java.util.List.of(changed));

        var report = importer.importData(corrected);
        entityManager.flush();
        entityManager.clear();

        assertThat(report.totalCreated()).isZero();
        assertThat(count("degree_program")).isEqualTo(2);
        DegreeProgram updated = programs.findByUniversitySlugAndSlug("synthetic-import-university", "full-program")
                .orElseThrow();
        assertThat(updated.getName()).endsWith("(renamed)");
        assertThat(updated.getDurationMonths()).isEqualTo(18);
        assertThat(updated.getTeachingLanguageCodes()).containsExactly("en");
        assertThat(updated.getVerificationStatus()).isEqualTo(VerificationStatus.UNVERIFIED);
        assertThat(updated.getOfficialApplicationUrl()).isNull();
    }

    @Test
    void intakesAreKeyedByLabelYearAndApplicantCategory() throws IOException {
        importer.importFrom(synthetic());
        entityManager.flush();
        // Same label and year, different applicant category: two distinct rows, both kept.
        assertThat(count("program_intake")).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "select count(*) from program_intake where applicant_category = 'DOMESTIC'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void conflictingTuitionIsRefusedNotSilentlyOverwritten() throws IOException {
        importer.importFrom(synthetic());
        entityManager.flush();
        ReferenceData data = ReferenceDataImporter.parse(synthetic());
        var p = data.programs().get(0);
        var otherAmount = new ReferenceData.TuitionData("2027/2028", StudentCategory.INTERNATIONAL,
                new BigDecimal("9999"), "EUR", p.tuition().get(0).billingPeriod(), p.tuition().get(0).source());
        var conflicting = new ReferenceData.ProgramData(p.university(), p.slug(), p.name(), p.degreeLevel(),
                p.field(), p.campusCity(), p.studyMode(), p.durationMonths(), p.teachingLanguage(), p.languages(),
                p.officialProgramUrl(), p.officialApplicationUrl(), p.verification(), p.sources(), p.intakes(),
                java.util.List.of(otherAmount), p.requirements());
        var file = new ReferenceData(1, data.countries(), data.fields(), data.sources(), data.universities(),
                java.util.List.of(conflicting));

        assertThatThrownBy(() -> importer.importData(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists with a different value");
    }

    @Test
    void invalidFilesAreRejectedBeforeAnythingIsWritten() throws IOException {
        ReferenceData data = ReferenceDataImporter.parse(synthetic());
        var p = data.programs().get(0);
        // An application URL on an UNVERIFIED program must never get in.
        var bad = new ReferenceData.ProgramData(p.university(), p.slug(), p.name(), p.degreeLevel(), p.field(),
                p.campusCity(), p.studyMode(), p.durationMonths(), p.teachingLanguage(), p.languages(),
                p.officialProgramUrl(), "https://example.invalid/import/apply",
                new ReferenceData.VerificationData(VerificationStatus.UNVERIFIED, null), p.sources(), p.intakes(),
                p.tuition(), p.requirements());
        var file = new ReferenceData(1, data.countries(), data.fields(), data.sources(), data.universities(),
                java.util.List.of(bad));

        assertThatThrownBy(() -> importer.importData(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("application URL requires a VERIFIED program");
        assertThat(count("degree_program")).isZero();
        assertThat(count("country")).isZero();
    }

    @Test
    void unknownPropertiesInTheFileAreRejected() {
        String json = "{\"schemaVersion\":1,\"programz\":[]}";
        assertThatThrownBy(() -> ReferenceDataImporter.parse(
                new java.io.ByteArrayInputStream(json.getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("could not be parsed");
    }
}

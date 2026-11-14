package com.pathwayai.backend.program.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pathwayai.backend.TestcontainersConfiguration;
import com.pathwayai.backend.program.entity.DegreeProgram;
import com.pathwayai.backend.program.entity.VerificationStatus;
import com.pathwayai.backend.program.importer.ReferenceData.ProgramData;
import com.pathwayai.backend.program.importer.ReferenceData.RetractionData;
import com.pathwayai.backend.program.importer.ReferenceData.RetractionType;
import com.pathwayai.backend.program.repository.DegreeProgramRepository;
import com.pathwayai.backend.tuition.entity.StudentCategory;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.UnaryOperator;
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

    private static final String UNI = "synthetic-import-university";

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

    /** The synthetic file with its first program rewritten (records are immutable, so rebuild). */
    private static ReferenceData withFirstProgram(UnaryOperator<ProgramData> change) throws IOException {
        ReferenceData d = ReferenceDataImporter.parse(synthetic());
        return new ReferenceData(1, d.countries(), d.fields(), d.sources(), d.universities(),
                List.of(change.apply(d.programs().get(0))), List.of());
    }

    private static ProgramData copy(ProgramData p, VerificationStatusHolder v, List<ReferenceData.TuitionData> tuition,
                                    List<ReferenceData.IntakeData> intakes, String applicationUrl,
                                    String applicationCycle) {
        return new ProgramData(p.university(), p.slug(), p.name(), p.degreeLevel(), p.field(), p.campusCity(),
                p.studyMode(), p.durationMonths(), p.teachingLanguage(), p.languages(), p.officialProgramUrl(),
                applicationUrl, v.value(), p.sources(), intakes, tuition, p.requirements(),
                p.officialAdmissionsUrl(), applicationCycle);
    }

    private record VerificationStatusHolder(ReferenceData.VerificationData value) {
    }

    @Test
    void firstImportCreatesEverythingTheFileDescribes() throws IOException {
        var report = importer.importFrom(synthetic());

        assertThat(report.created()).containsEntry("countries", 1).containsEntry("fields", 2)
                .containsEntry("sources", 3).containsEntry("universities", 1).containsEntry("programs", 2)
                .containsEntry("intakes", 2).containsEntry("tuition", 3).containsEntry("requirements", 1);
        entityManager.flush();
        entityManager.clear();

        DegreeProgram full = programs.findByUniversitySlugAndSlug(UNI, "full-program").orElseThrow();
        assertThat(full.getVerificationStatus()).isEqualTo(VerificationStatus.VERIFIED);
        assertThat(full.getOfficialApplicationUrl()).isEqualTo("https://example.invalid/import/apply");
        assertThat(full.getOfficialApplicationCycle()).isEqualTo("2099");
        assertThat(full.getOfficialAdmissionsUrl()).isEqualTo("https://example.invalid/import/admissions");
        assertThat(full.getTeachingLanguageCodes()).containsExactlyInAnyOrder("en", "de");
        assertThat(full.getAcademicField().getParent().getSlug()).isEqualTo("synthetic-science");
        assertThat(full.getSources()).hasSize(3);

        // A sparse record imports fine: unknown stays unknown, and it is not marked verified.
        DegreeProgram sparse = programs.findByUniversitySlugAndSlug(UNI, "sparse-program").orElseThrow();
        assertThat(sparse.getVerificationStatus()).isEqualTo(VerificationStatus.UNVERIFIED);
        assertThat(sparse.getAcademicField()).isNull();
        assertThat(sparse.getOfficialApplicationUrl()).isNull();
        assertThat(sparse.getOfficialAdmissionsUrl()).isNull();
        assertThat(sparse.getDurationMonths()).isNull();
        assertThat(sparse.getTeachingLanguageCodes()).isEmpty();
    }

    @Test
    void tuitionKeepsTheExactPeriodTheSourceStated() throws IOException {
        importer.importFrom(synthetic());
        entityManager.flush();
        // printed year, a start-date range, and no stated period at all: three distinct rows, nothing relabelled
        assertThat(jdbc.queryForObject("select count(*) from tuition_fee where academic_year = '2099/2100'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from tuition_fee where academic_year is null "
                + "and effective_from = date '2000-08-01' and effective_to = date '2001-07-31'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from tuition_fee where academic_year is null "
                + "and effective_from is null and effective_to is null", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select scope_note from tuition_fee where billing_period = 'PER_SEMESTER'",
                String.class)).isEqualTo("Synthetic: the source states no period");
    }

    @Test
    void importingTheSameFileAgainChangesNothing() throws IOException {
        importer.importFrom(synthetic());
        entityManager.flush();
        String[] tables = {"country", "academic_field", "source", "university", "degree_program", "program_intake",
                "tuition_fee", "academic_requirement", "program_language", "program_source"};
        int[] before = java.util.Arrays.stream(tables).mapToInt(this::count).toArray();

        var second = importer.importFrom(synthetic());
        entityManager.flush();
        var third = importer.importFrom(synthetic());
        entityManager.flush();

        assertThat(second.totalCreated()).isZero();
        assertThat(third.totalCreated()).isZero();
        assertThat(java.util.Arrays.stream(tables).mapToInt(this::count).toArray()).containsExactly(before);
    }

    @Test
    void aCorrectedFileUpdatesInPlaceWithoutDuplicates() throws IOException {
        importer.importFrom(synthetic());
        entityManager.flush();
        ReferenceData data = withFirstProgram(p -> new ProgramData(p.university(), p.slug(),
                "Synthetic Full Program (renamed)", p.degreeLevel(), "synthetic-science", null, null, 18, "English",
                List.of("en"), p.officialProgramUrl(), null,
                new ReferenceData.VerificationData(VerificationStatus.UNVERIFIED, null), p.sources(), p.intakes(),
                List.of(), List.of(), null, null));

        var report = importer.importData(data);
        entityManager.flush();
        entityManager.clear();

        assertThat(report.totalCreated()).isZero();
        assertThat(count("degree_program")).isEqualTo(2);
        DegreeProgram updated = programs.findByUniversitySlugAndSlug(UNI, "full-program").orElseThrow();
        assertThat(updated.getName()).endsWith("(renamed)");
        assertThat(updated.getDurationMonths()).isEqualTo(18);
        assertThat(updated.getTeachingLanguageCodes()).containsExactly("en");
        assertThat(updated.getVerificationStatus()).isEqualTo(VerificationStatus.UNVERIFIED);
        assertThat(updated.getOfficialApplicationUrl()).isNull();
        assertThat(updated.getOfficialApplicationCycle()).isNull();
        assertThat(updated.getOfficialAdmissionsUrl()).isNull();
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
    void previousYearFactsAreKeptWhenANewYearIsAdded() throws IOException {
        importer.importFrom(synthetic());
        entityManager.flush();
        var newYear = new ReferenceData.TuitionData("2100/2101", StudentCategory.INTERNATIONAL,
                new BigDecimal("1300"), "EUR", com.pathwayai.backend.tuition.entity.BillingPeriod.PER_YEAR,
                "https://example.invalid/import/program", null, null, null);
        ReferenceData data = withFirstProgram(p -> {
            var all = new java.util.ArrayList<>(p.tuition());
            all.add(newYear);
            return copy(p, new VerificationStatusHolder(p.verification()), all, p.intakes(),
                    p.officialApplicationUrl(), p.officialApplicationCycle());
        });

        var report = importer.importData(data);
        entityManager.flush();

        assertThat(report.created()).containsEntry("tuition", 1);
        // the 2099/2100 figure is untouched; the new year is a new row
        assertThat(jdbc.queryForObject("select count(*) from tuition_fee where academic_year in "
                + "('2099/2100','2100/2101')", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select amount from tuition_fee where academic_year = '2099/2100'",
                BigDecimal.class)).isEqualByComparingTo("1234.50");
    }

    @Test
    void conflictingTuitionIsRefusedNotSilentlyOverwritten() throws IOException {
        importer.importFrom(synthetic());
        entityManager.flush();
        var otherAmount = new ReferenceData.TuitionData("2099/2100", StudentCategory.INTERNATIONAL,
                new BigDecimal("9999"), "EUR", com.pathwayai.backend.tuition.entity.BillingPeriod.PER_YEAR,
                "https://example.invalid/import/program", null, null, null);
        ReferenceData file = withFirstProgram(p -> copy(p, new VerificationStatusHolder(p.verification()),
                List.of(otherAmount), p.intakes(), p.officialApplicationUrl(), p.officialApplicationCycle()));

        assertThatThrownBy(() -> importer.importData(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists with a different value");
    }

    @Test
    void anExplicitRetractionRemovesAnErroneousFactOnceAndIsSafeToRepeat() throws IOException {
        importer.importFrom(synthetic());
        entityManager.flush();
        var retraction = new RetractionData(RetractionType.TUITION, UNI + "--full-program", "2099/2100",
                StudentCategory.INTERNATIONAL, null, null, "Synthetic: the year was inferred, not stated");
        ReferenceData d = ReferenceDataImporter.parse(synthetic());
        var withRetraction = new ReferenceData(1, d.countries(), d.fields(), d.sources(), d.universities(),
                List.of(), List.of(retraction));

        var first = importer.importData(withRetraction);
        entityManager.flush();
        var second = importer.importData(withRetraction);

        assertThat(first.created()).containsEntry("retracted tuition", 1);
        assertThat(second.totalCreated()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from tuition_fee where academic_year = '2099/2100'",
                Integer.class)).isZero();
        // nothing else was touched
        assertThat(count("tuition_fee")).isEqualTo(2);
        assertThat(count("degree_program")).isEqualTo(2);
    }

    @Test
    void invalidFilesAreRejectedBeforeAnythingIsWritten() throws IOException {
        // An application URL on an UNVERIFIED program must never get in.
        ReferenceData file = withFirstProgram(p -> copy(p,
                new VerificationStatusHolder(new ReferenceData.VerificationData(VerificationStatus.UNVERIFIED, null)),
                p.tuition(), p.intakes(), "https://example.invalid/import/apply", "2099"));

        assertThatThrownBy(() -> importer.importData(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("application URL requires a VERIFIED program");
        assertThat(count("degree_program")).isZero();
        assertThat(count("country")).isZero();
    }

    @Test
    void anApplicationUrlWithoutACycleIsRejected() throws IOException {
        ReferenceData file = withFirstProgram(p -> copy(p, new VerificationStatusHolder(p.verification()),
                p.tuition(), p.intakes(), p.officialApplicationUrl(), null));
        assertThatThrownBy(() -> importer.importData(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires officialApplicationCycle");
        assertThat(count("degree_program")).isZero();
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

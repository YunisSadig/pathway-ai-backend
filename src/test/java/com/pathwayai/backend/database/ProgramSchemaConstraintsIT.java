package com.pathwayai.backend.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pathwayai.backend.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * The V2 integrity rules, asserted at the database (raw SQL, so Java guards cannot mask a missing constraint).
 * One expected violation per test: PostgreSQL aborts the transaction after the first.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ProgramSchemaConstraintsIT {

    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.update("insert into country(iso2_code, name) values ('XA', 'Synthetic Land A')");
        jdbc.update("insert into university(country_id, slug, name) select id, 'uni', 'Uni' from country");
        jdbc.update("insert into source(url, title, source_type, retrieved_on) "
                + "values ('https://example.invalid/s', 't', 'OTHER', date '2026-01-01')");
    }

    private void program(String slug, String extraColumns, String extraValues) {
        jdbc.update("insert into degree_program(university_id, slug, name, degree_level" + extraColumns + ") "
                + "select id, '" + slug + "', 'N', 'BACHELOR'" + extraValues + " from university where slug = 'uni'");
    }

    @Test
    void minimalProgramIsAcceptedBecauseEveryNewColumnIsOptional() {
        program("minimal", "", "");
        assertThat(jdbc.queryForObject("select verification_status from degree_program", String.class))
                .isEqualTo("UNVERIFIED");
    }

    @Test
    void anApplicationUrlRequiresAVerifiedProgram() {
        assertThatThrownBy(() -> program("p", ", official_application_url", ", 'https://example.invalid/apply'"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_program_application_url_verified");
    }

    @Test
    void aVerifiedProgramRequiresAVerificationDate() {
        assertThatThrownBy(() -> program("p", ", verification_status", ", 'VERIFIED'"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_program_verified_has_date");
    }

    @Test
    void verifiedProgramWithDateAndApplicationUrlIsAccepted() {
        program("p", ", verification_status, verified_on, official_application_url",
                ", 'VERIFIED', date '2026-01-01', 'https://example.invalid/apply'");
        assertThat(jdbc.queryForObject("select count(*) from degree_program", Integer.class)).isEqualTo(1);
    }

    @Test
    void unknownStudyModeAndVerificationStatusAreRejected() {
        assertThatThrownBy(() -> program("p", ", study_mode", ", 'ON_THE_MOON'"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_program_study_mode");
    }

    @Test
    void urlsMustUseHttpOrHttps() {
        assertThatThrownBy(() -> program("p", ", official_program_url", ", 'javascript:alert(1)'"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_program_program_url_scheme");
    }

    @Test
    void programSlugsCannotContainTheIdentifierSeparator() {
        assertThatThrownBy(() -> program("bad--slug", "", ""))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_program_slug_format");
    }

    @Test
    void programSlugIsUniquePerUniversityButNotGlobally() {
        jdbc.update("insert into university(country_id, slug, name) select id, 'uni2', 'Uni 2' from country");
        program("same", "", "");
        jdbc.update("insert into degree_program(university_id, slug, name, degree_level) "
                + "select id, 'same', 'N', 'MASTER' from university where slug = 'uni2'");
        assertThat(jdbc.queryForObject("select count(*) from degree_program where slug = 'same'", Integer.class))
                .isEqualTo(2);
        assertThatThrownBy(() -> program("same", "", ""))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uq_program_university_slug");
    }

    @Test
    void languageCodesMustBeLowercaseIsoStyle() {
        program("p", "", "");
        assertThatThrownBy(() -> jdbc.update(
                "insert into program_language(program_id, language_code) select id, 'English' from degree_program"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_program_language_code");
    }

    @Test
    void aProgramHasEachLanguageAtMostOnce() {
        program("p", "", "");
        jdbc.update("insert into program_language(program_id, language_code) select id, 'en' from degree_program");
        assertThatThrownBy(() -> jdbc.update(
                "insert into program_language(program_id, language_code) select id, 'en' from degree_program"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anIntakeMustCiteASourceAndNaturalKeysAreUnique() {
        program("p", "", "");
        assertThatThrownBy(() -> jdbc.update("insert into program_intake(program_id, label) "
                + "select id, 'Autumn' from degree_program"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateIntakeWithNullYearAndCategoryIsRejected() {
        program("p", "", "");
        String insert = "insert into program_intake(program_id, source_id, label) "
                + "select p.id, s.id, 'Autumn' from degree_program p, source s";
        jdbc.update(insert);
        assertThatThrownBy(() -> jdbc.update(insert))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uq_intake_natural");
    }

    @Test
    void intakeApplicantCategoryIsRestricted() {
        program("p", "", "");
        assertThatThrownBy(() -> jdbc.update("insert into program_intake(program_id, source_id, label, "
                + "applicant_category) select p.id, s.id, 'Autumn', 'ALIEN' from degree_program p, source s"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_intake_category");
    }

    @Test
    void academicFieldSlugFormatAndSelfParentAreRejected() {
        assertThatThrownBy(() -> jdbc.update("insert into academic_field(slug, name) values ('Bad Slug', 'x')"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_academic_field_slug_format");
    }

    @Test
    void deletingAProgramRemovesItsLanguagesIntakesAndSourceLinks() {
        program("p", "", "");
        jdbc.update("insert into program_language(program_id, language_code) select id, 'en' from degree_program");
        jdbc.update("insert into program_source(program_id, source_id) "
                + "select p.id, s.id from degree_program p, source s");
        jdbc.update("insert into program_intake(program_id, source_id, label) "
                + "select p.id, s.id, 'Autumn' from degree_program p, source s");
        jdbc.update("delete from degree_program");
        assertThat(jdbc.queryForObject("select count(*) from program_language", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from program_source", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from program_intake", Integer.class)).isZero();
        // The shared source itself stays.
        assertThat(jdbc.queryForObject("select count(*) from source", Integer.class)).isEqualTo(1);
    }
}

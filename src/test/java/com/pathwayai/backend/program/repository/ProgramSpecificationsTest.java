package com.pathwayai.backend.program.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProgramSpecificationsTest {

    @Test
    void escapeLikeMakesWildcardsLiteral() {
        assertThat(ProgramSpecifications.escapeLike("100%_done\\")).isEqualTo("100\\%\\_done\\\\");
        assertThat(ProgramSpecifications.escapeLike("plain")).isEqualTo("plain");
    }

    @Test
    void noFilterSpecificationsAreNeverNull() {
        // Spring Data 4 rejects null specifications in and(); "no filter" must be an explicit match-all.
        assertThat(ProgramSpecifications.nameContains(null)).isNotNull();
        assertThat(ProgramSpecifications.nameContains("  ")).isNotNull();
        assertThat(ProgramSpecifications.atLevel(null)).isNotNull();
        assertThat(ProgramSpecifications.inField("")).isNotNull();
        assertThat(ProgramSpecifications.inCountry(null)).isNotNull();
        assertThat(ProgramSpecifications.atUniversity(null)).isNotNull();
        assertThat(ProgramSpecifications.taughtIn(null)).isNotNull();
        assertThat(ProgramSpecifications.tuitionAtMost(null, null, null, 2026, java.time.LocalDate.of(2026, 11, 11))).isNotNull();
    }
}

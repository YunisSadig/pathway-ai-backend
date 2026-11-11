package com.pathwayai.backend.refresh.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pathwayai.backend.TestcontainersConfiguration;
import com.pathwayai.backend.refresh.extract.ExtractedFact;
import com.pathwayai.backend.refresh.extract.ExtractedFact.FactType;
import com.pathwayai.backend.refresh.registry.SourceMonitor;
import com.pathwayai.backend.refresh.registry.SourceMonitorRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * COMPARE, STAGE and APPLY OR REVIEW against PostgreSQL with synthetic programs. Transactional: each test rolls back.
 * Adapters are not involved; facts are handed to the pipeline exactly as an adapter would produce them.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ChangePipelineIT {

    static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");

    @Autowired ChangePipeline pipeline;
    @Autowired StagedChangeService review;
    @Autowired SourceMonitorRepository monitors;
    @Autowired JdbcTemplate jdbc;

    long programId;
    long sourceId;
    SourceMonitor monitor;

    @BeforeEach
    void seed() {
        jdbc.update("insert into country (iso2_code, name) values ('ZP', 'Pipelineland')");
        long country = jdbc.queryForObject("select id from country where iso2_code = 'ZP'", Long.class);
        long university = jdbc.queryForObject(
                "insert into university (country_id, slug, name) values (?, 'zz-pipeline-u', 'Synthetic U') returning id", Long.class, country);
        programId = jdbc.queryForObject("""
                insert into degree_program (university_id, slug, name, degree_level)
                values (?, 'zz-pipeline-p', 'Synthetic P', 'MASTER') returning id""", Long.class, university);
        sourceId = jdbc.queryForObject("""
                insert into source (url, title, publisher, source_type, retrieved_on)
                values ('https://www.zz-pipeline.example/fees', 'Fees', 'Synthetic', 'UNIVERSITY_WEBSITE', current_date) returning id""", Long.class);
        monitors.register(sourceId, new SourceMonitor.Spec("https://www.zz-pipeline.example/fees", university, programId,
                "TUITION", "", "ADAPTER", "test-adapter", "1", 7), "VERIFIED", NOW);
        monitor = monitors.findBySourceId(sourceId).orElseThrow();
    }

    static ExtractedFact tuition(String year, String amount) {
        Map<String, String> v = new HashMap<>(Map.of("amount", amount, "currency", "EUR", "billingPeriod", "PER_YEAR",
                "studentCategory", "INTERNATIONAL"));
        if (year != null) {
            v.put("academicYear", year);
        }
        return ExtractedFact.of(FactType.TUITION, "INTERNATIONAL|PER_YEAR|" + year, v);
    }

    static ExtractedFact deadline(String date) {
        return ExtractedFact.of(FactType.DEADLINE, "Autumn 2027", Map.of("label", "Autumn 2027", "applicationDeadline", date,
                "applicantCategory", "INTERNATIONAL"));
    }

    void publishFee(String year, String amount) {
        jdbc.update("""
                insert into tuition_fee (program_id, source_id, academic_year, student_category, amount, currency, billing_period)
                values (?, ?, ?, 'INTERNATIONAL', ?::numeric, 'EUR', 'PER_YEAR')""", programId, sourceId, year, amount);
    }

    int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    @Test
    void aFeeForANewYearWaitsForReviewAndApprovalAddsARowWithoutTouchingHistory() {
        publishFee("2025/2026", "8000");
        var out = pipeline.process(monitor, List.of(tuition("2026/2027", "9000")), "p1", NOW);
        assertThat(out.pendingReview()).isEqualTo(1);
        assertThat(out.autoApplied()).isZero();
        assertThat(count("select count(*) from tuition_fee where program_id = ?", programId)).isEqualTo(1); // not published yet

        long id = review.pending().get(0).id();
        review.approve(id, "reviewer");
        assertThat(count("select count(*) from tuition_fee where program_id = ?", programId)).isEqualTo(2);
        // history: the old year is exactly as it was, the new year is a new row citing the same source
        assertThat(jdbc.queryForObject("select amount::text from tuition_fee where program_id = ? and academic_year = '2025/2026'", String.class, programId)).isEqualTo("8000.00");
        assertThat(jdbc.queryForObject("select amount::text from tuition_fee where program_id = ? and academic_year = '2026/2027'", String.class, programId)).isEqualTo("9000.00");
        assertThat(jdbc.queryForObject("select source_id from tuition_fee where academic_year = '2026/2027'", Long.class)).isEqualTo(sourceId);
        assertThat(jdbc.queryForObject("select status || '/' || decided_by from staged_change where id = ?", String.class, id)).isEqualTo("APPROVED/reviewer");
    }

    @Test
    void aDifferentFeeForAnExistingPeriodIsAConflictThatCannotBeApprovedOver() {
        publishFee("2026/2027", "9000");
        var out = pipeline.process(monitor, List.of(tuition("2026/2027", "9500")), "p1", NOW);
        assertThat(out.pendingReview()).isEqualTo(1);
        var item = review.pending().get(0);
        assertThat(item.oldValue()).isEqualTo("9000 EUR");
        assertThat(item.reason()).contains("differs from the published value");

        assertThatThrownBy(() -> review.approve(item.id(), "reviewer"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("never overwritten");
        assertThat(jdbc.queryForObject("select amount::text from tuition_fee where program_id = ?", String.class, programId)).isEqualTo("9000.00");
        assertThat(review.pending()).hasSize(1); // still open; the reviewer must reject it or correct it through the importer
        review.reject(item.id(), "reviewer", "page is a draft");
        assertThat(review.pending()).isEmpty();
        assertThat(jdbc.queryForObject("select reason from staged_change where id = ?", String.class, item.id())).isEqualTo("page is a draft");
    }

    @Test
    void anIdenticalFeeIsUnchangedAndStagesNothing() {
        publishFee("2026/2027", "9000");
        var out = pipeline.process(monitor, List.of(tuition("2026/2027", "9000.00")), "p1", NOW);
        assertThat(out.unchanged()).isEqualTo(1);
        assertThat(count("select count(*) from staged_change")).isZero();
    }

    @Test
    void contradictoryFeesInOneExtractionAreBothRejected() {
        var out = pipeline.process(monitor, List.of(tuition("2026/2027", "9000"), tuition("2026/2027", "12000")), "p1", NOW);
        assertThat(out.rejected()).hasSize(2).allMatch(r -> r.contains("contradictory"));
        assertThat(count("select count(*) from staged_change")).isZero();
    }

    @Test
    void ambiguousOrInvalidValuesAreRejectedAndNeverStaged() {
        var out = pipeline.process(monitor, List.of(tuition("2026/2027", "20,000"), tuition("2026/2028", "9000"),
                ExtractedFact.of(FactType.TUITION, "k3", Map.of("amount", "100", "currency", "EURO", "billingPeriod", "PER_YEAR",
                        "studentCategory", "DOMESTIC", "academicYear", "2026/2027"))), "p1", NOW);
        assertThat(out.rejectedCount()).isEqualTo(3);
        assertThat(count("select count(*) from staged_change")).isZero();
        assertThat(count("select count(*) from tuition_fee")).isZero();
    }

    @Test
    void processingTheSamePageTwiceStagesNothingNew() {
        var facts = List.of(tuition("2026/2027", "9000"));
        assertThat(pipeline.process(monitor, facts, "p1", NOW).pendingReview()).isEqualTo(1);
        var again = pipeline.process(monitor, facts, "p1", NOW.plusSeconds(3600));
        assertThat(again.pendingReview()).isZero();
        assertThat(again.alreadySeen()).isEqualTo(1);
        // even under a new parser version the same fact is recognised
        assertThat(pipeline.process(monitor, facts, "p2", NOW.plusSeconds(7200)).alreadySeen()).isEqualTo(1);
        assertThat(count("select count(*) from staged_change")).isEqualTo(1);
    }

    @Test
    void aFeeWithNoStatedYearStaysUndatedAndIsStagedNotInvented() {
        var out = pipeline.process(monitor, List.of(tuition(null, "9000")), "p1", NOW);
        assertThat(out.pendingReview()).isEqualTo(1);
        review.approve(review.pending().get(0).id(), "reviewer");
        // no year was invented: the row has none, so the API reports UNKNOWN_YEAR
        assertThat(count("select count(*) from tuition_fee where program_id = ? and academic_year is null", programId)).isEqualTo(1);
    }

    @Test
    void aNewDeadlineWaitsForReviewAndIsAddedAsItsOwnRow() {
        var out = pipeline.process(monitor, List.of(deadline("2027-01-15")), "p1", NOW);
        assertThat(out.pendingReview()).isEqualTo(1);
        review.approve(review.pending().get(0).id(), "reviewer");
        assertThat(jdbc.queryForObject("select application_deadline::text from program_intake where program_id = ?", String.class, programId)).isEqualTo("2027-01-15");
    }

    @Test
    void aMovedDeadlineIsAConflictAndTheOldDateIsKept() {
        jdbc.update("insert into program_intake (program_id, source_id, label, application_deadline, applicant_category) values (?, ?, 'Autumn 2027', '2027-01-05', 'INTERNATIONAL')", programId, sourceId);
        var out = pipeline.process(monitor, List.of(deadline("2027-01-15")), "p1", NOW);
        assertThat(out.pendingReview()).isEqualTo(1);
        var item = review.pending().get(0);
        assertThat(item.oldValue()).isEqualTo("2027-01-05");
        assertThatThrownBy(() -> review.approve(item.id(), "reviewer")).hasMessageContaining("never overwritten");
        assertThat(jdbc.queryForObject("select application_deadline::text from program_intake where program_id = ?", String.class, programId)).isEqualTo("2027-01-05");
    }

    @Test
    void requirementsAreStagedButCanNeverBePublishedAutomatically() {
        var req = ExtractedFact.of(FactType.REQUIREMENT, "IELTS", Map.of("type", "LANGUAGE_TEST", "applicationCycle", "2027",
                "description", "IELTS 6.5 overall"));
        assertThat(pipeline.process(monitor, List.of(req), "p1", NOW).pendingReview()).isEqualTo(1);
        assertThatThrownBy(() -> review.approve(review.pending().get(0).id(), "reviewer"))
                .hasMessageContaining("cannot be published automatically");
        assertThat(count("select count(*) from academic_requirement")).isZero();
    }

    @Test
    void ifTheFactWasPublishedMeanwhileApprovalOnlyMarksItSuperseded() {
        pipeline.process(monitor, List.of(tuition("2026/2027", "9000")), "p1", NOW);
        long id = review.pending().get(0).id();
        publishFee("2026/2027", "9000"); // e.g. through the reference-data import
        review.approve(id, "reviewer");
        assertThat(jdbc.queryForObject("select status from staged_change where id = ?", String.class, id)).isEqualTo("SUPERSEDED");
        assertThat(count("select count(*) from tuition_fee where program_id = ?", programId)).isEqualTo(1);
    }

    @Test
    void aSourceNotBoundToOneProgramCannotAttributeProgramFacts() {
        jdbc.update("update source_monitor set program_id = null where source_id = ?", sourceId);
        var shared = monitors.findBySourceId(sourceId).orElseThrow();
        var out = pipeline.process(shared, List.of(tuition("2026/2027", "9000")), "p1", NOW);
        assertThat(out.rejected()).singleElement().asString().contains("not bound to a single program");
        assertThat(count("select count(*) from staged_change")).isZero();
    }

    @Test
    void aDecidedChangeCannotBeDecidedAgain() {
        pipeline.process(monitor, List.of(tuition("2026/2027", "9000")), "p1", NOW);
        long id = review.pending().get(0).id();
        review.approve(id, "reviewer");
        assertThatThrownBy(() -> review.approve(id, "reviewer")).hasMessageContaining("not pending");
        assertThatThrownBy(() -> review.reject(id, "reviewer", null)).hasMessageContaining("not pending");
        assertThatThrownBy(() -> review.approve(999_999L, "reviewer")).hasMessageContaining("no staged change");
    }

    @Test
    void theDatabaseItselfRefusesAnUndecidedChangeThatClaimsADecision() {
        assertThatThrownBy(() -> jdbc.update("""
                insert into staged_change (source_id, fact_type, new_value, risk, status, parser_version, dedupe_key, detected_at)
                values (?, 'TUITION', '{}', 'HIGH', 'APPROVED', 'p', repeat('a', 64), now())""", sourceId))
                .hasMessageContaining("ck_staged_decided");
    }
}

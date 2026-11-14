package com.pathwayai.backend.provider.aalto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pathwayai.backend.TestcontainersConfiguration;
import com.pathwayai.backend.refresh.fetch.ApprovedHosts;
import com.pathwayai.backend.refresh.pipeline.StagedChangeService;
import com.pathwayai.backend.refresh.registry.SourceMonitor;
import com.pathwayai.backend.refresh.registry.SourceMonitorRepository;
import com.pathwayai.backend.refresh.schedule.RefreshService;
import com.pathwayai.backend.refresh.support.FakeTransport;
import com.pathwayai.backend.refresh.support.RefreshTestConfig;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Tuition synchronisation from an official fee page, end to end on PostgreSQL. One page states fees for several
 * programs; the sync confirms the stored, already-curated fees, stages anything that differs and never overwrites
 * a fee that belongs to a stated period. The page is a scripted copy of a real recorded one.
 */
@SpringBootTest(properties = "app.refresh.min-host-interval-ms=0")
@Import({TestcontainersConfiguration.class, RefreshTestConfig.class})
class AaltoTuitionSyncIT {

    static final String PAGE = "https://www.aalto.fi/en/admission-services/scholarships-and-tuition-fees";
    static final Instant T0 = Instant.parse("2026-11-01T00:00:00Z");
    static final Instant T1 = Instant.parse("2026-11-20T00:00:00Z");

    @Autowired RefreshService refresh;
    @Autowired SourceMonitorRepository monitors;
    @Autowired StagedChangeService review;
    @Autowired ApprovedHosts approvedHosts;
    @Autowired FakeTransport transport;
    @Autowired JdbcTemplate jdbc;

    long archId;
    long mechId;
    long sourceId;

    @BeforeEach
    void seed() {
        transport.clearRequests();
        jdbc.update("insert into country (iso2_code, name) values ('ZA', 'Aaltoland')");
        long country = jdbc.queryForObject("select id from country where iso2_code = 'ZA'", Long.class);
        long uni = jdbc.queryForObject("""
                insert into university (country_id, slug, name, website_url, verification_status, verified_on)
                values (?, 'zz-aalto-u', 'Synthetic Aalto Stand-in', 'https://www.aalto.fi/', 'VERIFIED', current_date) returning id""",
                Long.class, country);
        archId = program(uni, "architecture-msc");
        mechId = program(uni, "mechanical-engineering-msc");
        sourceId = jdbc.queryForObject("""
                insert into source (url, title, publisher, source_type, retrieved_on)
                values (?, 'Tuition page', 'Aalto University', 'UNIVERSITY_WEBSITE', date '2026-11-11') returning id""", Long.class, PAGE);
        // the fees a person curated earlier from this same page, plus an older era that must survive untouched
        fee(archId, "20000", "2025-08-01", null);
        fee(mechId, "17000", "2025-08-01", null);
        fee(mechId, "15000", null, "2024-08-01");
        monitors.register(sourceId, new SourceMonitor.Spec(PAGE, uni, null, "TUITION", "", "CONTENT_HASH", "content-hash", "1", 7), "VERIFIED", T0);
        monitors.assignAdapter(sourceId, AaltoTuitionAdapter.NAME, AaltoTuitionAdapter.VERSION, null, T0);
        approvedHosts.refresh();
        serve(AaltoTuitionAdapterTest.html());
    }

    @AfterEach
    void clean() {
        jdbc.update("delete from staged_change");
        jdbc.update("delete from source_check");
        jdbc.update("delete from source_monitor");
        jdbc.update("delete from university where slug = 'zz-aalto-u'");
        jdbc.update("delete from source where url = ?", PAGE);
        jdbc.update("delete from country where iso2_code = 'ZA'");
    }

    long program(long uni, String slug) {
        return jdbc.queryForObject("insert into degree_program (university_id, slug, name, degree_level) values (?, ?, ?, 'MASTER') returning id",
                Long.class, uni, slug, "Synthetic " + slug);
    }

    void fee(long program, String amount, String from, String to) {
        jdbc.update("""
                insert into tuition_fee (program_id, source_id, student_category, amount, currency, billing_period, effective_from, effective_to)
                values (?, ?, 'INTERNATIONAL', ?::numeric, 'EUR', 'PER_YEAR', ?::date, ?::date)""", program, sourceId, amount, from, to);
    }

    void serve(String html) {
        transport.reset(PAGE, FakeTransport.html(html));
    }

    RefreshService.RunSummary run(Instant at) {
        monitors.markDue(AaltoTuitionAdapter.NAME, at);
        return refresh.runDueForAdapter(AaltoTuitionAdapter.NAME, at);
    }

    List<String> fees() {
        return jdbc.queryForList("""
                select p.slug || ' ' || t.amount::int || ' from ' || coalesce(t.effective_from::text, '-') || ' to ' || coalesce(t.effective_to::text, '-') || ' #' || t.id
                from tuition_fee t join degree_program p on p.id = t.program_id order by 1""", String.class);
    }

    @Test
    void theStoredFeesAreConfirmedByTheOfficialPageAndNothingIsRewritten() {
        var before = fees();
        var first = run(T0.plusSeconds(60));

        assertThat(first.baseline()).isEqualTo(1);
        assertThat(first.pendingReview()).isZero();
        assertThat(first.rejectedFacts()).isZero();
        assertThat(fees()).as("the extractor read the page and every fee compared equal").isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from staged_change", Long.class)).isZero();

        var second = run(T1);
        assertThat(second.unchanged()).isEqualTo(1);
        assertThat(fees()).isEqualTo(before);
        var m = monitors.findBySourceId(sourceId).orElseThrow();
        assertThat(m.adapterName()).isEqualTo("aalto-tuition");
        assertThat(m.lastSuccessAt()).isEqualTo(T1);
    }

    @Test
    void aControlledChangeOfAnExistingPeriodsFeeIsStagedAndNeverOverwrites() {
        run(T0.plusSeconds(60));
        var before = fees();
        serve(AaltoTuitionAdapterTest.first(AaltoTuitionAdapterTest.html(), "€17 000 per academic year", "€18 500 per academic year"));
        var run = run(T1);

        assertThat(run.pendingReview()).isEqualTo(1);
        assertThat(fees()).as("the published fee for the stated period is untouched").isEqualTo(before);
        var staged = jdbc.queryForMap("select id, program_id, fact_type, old_value, new_value, risk from staged_change where status = 'PENDING_REVIEW'");
        assertThat(staged).containsEntry("fact_type", "TUITION").containsEntry("risk", "HIGH");
        assertThat(((Number) staged.get("program_id")).longValue()).isEqualTo(mechId);
        assertThat((String) staged.get("old_value")).isEqualTo("17000 EUR");
        assertThat((String) staged.get("new_value")).contains("18500");
        long id = ((Number) staged.get("id")).longValue();
        assertThatThrownBy(() -> review.approve(id, "test-reviewer")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Dated facts are never overwritten");
        review.reject(id, "test-reviewer", "controlled test change, not published by Aalto");
        assertThat(fees()).isEqualTo(before);
    }

    @Test
    void aNewlyStatedPeriodIsAddedAfterReviewAndTheOlderPeriodsStayExactlyAsTheyWere() {
        run(T0.plusSeconds(60));
        var before = fees();
        serve(AaltoTuitionAdapterTest.html().replace("</main>", "<div><table><tr><td><h4>Master’s programmes</h4></td><td><h4>"
                + "Tuition fees for rights to study beginning on or after 1 August 2026</h4></td></tr>"
                + "<tr><td>Field of Technology</td><td>€18 000 per academic year</td></tr>"
                + "<tr><td>Fields of Art and Architecture</td><td>€21 000 per academic year</td></tr></table></div></main>"));
        var run = run(T1);

        assertThat(run.pendingReview()).as("one new period per mapped program, never auto-published").isEqualTo(2);
        assertThat(fees()).isEqualTo(before);
        var ids = jdbc.queryForList("select id from staged_change where status = 'PENDING_REVIEW' order by id", Long.class);
        ids.forEach(id -> review.approve(id, "test-reviewer"));

        var after = fees();
        assertThat(after).hasSize(before.size() + 2);
        assertThat(after).containsAll(before);
        assertThat(after).anyMatch(f -> f.startsWith("mechanical-engineering-msc 18000 from 2026-08-01 to -"));
        assertThat(after).anyMatch(f -> f.startsWith("architecture-msc 21000 from 2026-08-01 to -"));
        // the previous period is not given an end date the page never stated
        assertThat(after).anyMatch(f -> f.startsWith("mechanical-engineering-msc 17000 from 2025-08-01 to -"));
        // and the pre-existing historical era is still there, still dated by its own end
        assertThat(after).anyMatch(f -> f.startsWith("mechanical-engineering-msc 15000 from - to 2024-08-01"));
    }

    @Test
    void aPageThatNoLongerStatesTheAudienceChangesNothingAndAsksForReview() {
        run(T0.plusSeconds(60));
        var before = fees();
        serve(AaltoTuitionAdapterTest.html().replace("outside the EU, EEA or Switzerland", "from somewhere"));
        var run = run(T1);

        assertThat(run.pendingReview()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select fact_type from staged_change where status = 'PENDING_REVIEW'", String.class)).isEqualTo("SOURCE_CONTENT");
        assertThat(fees()).isEqualTo(before);
    }

    @Test
    void aSourceNotBoundToAUniversityFailsVisiblyAndWorksOnceBound() {
        long uni = jdbc.queryForObject("select id from university where slug = 'zz-aalto-u'", Long.class);
        jdbc.update("update source_monitor set university_id = null where source_id = ?", sourceId);
        var before = fees();

        var broken = run(T0.plusSeconds(60));
        assertThat(broken.failed()).as("a misconfigured source is a recorded failure, not a vague review item").isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from staged_change", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select last_result from source_monitor where source_id = ?", String.class, sourceId)).isEqualTo("FAILED");

        monitors.assignAdapter(sourceId, AaltoTuitionAdapter.NAME, AaltoTuitionAdapter.VERSION, uni, T0.plusSeconds(120));
        var fixed = run(T1);
        assertThat(fixed.failed()).isZero();
        assertThat(fixed.pendingReview()).isZero();
        assertThat(fees()).isEqualTo(before);
        assertThat(monitors.findBySourceId(sourceId).orElseThrow().universityId()).isEqualTo(uni);
    }

    @Test
    void anOutageOnlyRecordsTheFailure() {
        run(T0.plusSeconds(60));
        var before = fees();
        transport.reset(PAGE, new IOException("timeout"));
        var run = run(T1);

        assertThat(run.failed()).isEqualTo(1);
        assertThat(fees()).isEqualTo(before);
        var m = monitors.findBySourceId(sourceId).orElseThrow();
        assertThat(m.failureCount()).isEqualTo(1);
        assertThat(m.lastSuccessAt()).isNotEqualTo(T1);
    }

    @Test
    void aMappedProgramTheUniversityDoesNotHaveIsIgnoredNotCreated() {
        jdbc.update("delete from degree_program where id = ?", archId);
        var run = run(T0.plusSeconds(60));
        assertThat(run.failed()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from degree_program where slug = 'architecture-msc' and university_id = (select id from university where slug = 'zz-aalto-u')", Long.class)).isZero();
    }
}

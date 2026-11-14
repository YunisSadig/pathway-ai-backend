package com.pathwayai.backend.refresh;

import static org.assertj.core.api.Assertions.assertThat;

import com.pathwayai.backend.TestcontainersConfiguration;
import com.pathwayai.backend.refresh.fetch.ApprovedHosts;
import com.pathwayai.backend.refresh.pipeline.StagedChangeService;
import com.pathwayai.backend.refresh.registry.SourceMonitor;
import com.pathwayai.backend.refresh.registry.SourceMonitorRepository;
import com.pathwayai.backend.refresh.report.MonitoringReportService;
import com.pathwayai.backend.refresh.schedule.RefreshService;
import com.pathwayai.backend.refresh.support.FakeTransport;
import com.pathwayai.backend.refresh.support.RefreshTestConfig;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The refresh pipeline end to end against PostgreSQL and a scripted transport (no live websites). The service
 * commits one transaction per source, so these tests commit real rows and delete them afterwards.
 */
@SpringBootTest(properties = "app.refresh.min-host-interval-ms=0")
@Import({TestcontainersConfiguration.class, RefreshTestConfig.class})
class RefreshServiceIT {

    static final Instant T0 = Instant.parse("2026-11-01T00:00:00Z");
    static final String HOST = "https://www.zz-refresh.example";

    @Autowired RefreshService refresh;
    @Autowired SourceMonitorRepository monitors;
    @Autowired StagedChangeService review;
    @Autowired MonitoringReportService report;
    @Autowired ApprovedHosts approvedHosts;
    @Autowired FakeTransport transport;
    @Autowired JdbcTemplate jdbc;

    long programId;
    long universityId;

    @BeforeEach
    void seed() {
        transport.clearRequests();
        jdbc.update("insert into country (iso2_code, name) values ('ZR', 'Refreshland')");
        long country = jdbc.queryForObject("select id from country where iso2_code = 'ZR'", Long.class);
        universityId = jdbc.queryForObject("""
                insert into university (country_id, slug, name, website_url, verification_status, verified_on)
                values (?, 'zz-refresh-u', 'Synthetic Refresh University', ?, 'VERIFIED', current_date) returning id""",
                Long.class, country, HOST + "/");
        programId = jdbc.queryForObject("""
                insert into degree_program (university_id, slug, name, degree_level)
                values (?, 'zz-refresh-p', 'Synthetic Refresh Program', 'MASTER') returning id""", Long.class, universityId);
    }

    @AfterEach
    void clean() {
        jdbc.update("delete from staged_change");
        jdbc.update("delete from source_check");
        jdbc.update("delete from source_monitor");
        jdbc.update("delete from university where slug like 'zz-refresh-%'");
        jdbc.update("delete from source where url like 'https://%zz-refresh.example%'");
        jdbc.update("delete from country where iso2_code = 'ZR'");
    }

    /** A source cited by the program, registered for monitoring. */
    long monitor(String path, String adapter, String method) {
        String url = HOST + path;
        long sourceId = jdbc.queryForObject("""
                insert into source (url, title, publisher, source_type, retrieved_on)
                values (?, 'Synthetic page', 'Synthetic', 'UNIVERSITY_WEBSITE', current_date) returning id""", Long.class, url);
        jdbc.update("insert into program_source (program_id, source_id) values (?, ?)", programId, sourceId);
        var spec = new SourceMonitor.Spec(url, universityId, programId, "PROGRAM", "", method, adapter, "1", 7);
        monitors.register(sourceId, spec, "VERIFIED", T0);
        approvedHosts.refresh();
        return sourceId;
    }

    SourceMonitor state(long sourceId) {
        return monitors.findBySourceId(sourceId).orElseThrow();
    }

    int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    @Test
    void baselineThenNotModifiedThenNoiseIgnoredThenARealChangeIsStagedExactlyOnce() {
        long id = monitor("/baseline", "content-hash", "CONTENT_HASH");
        String url = HOST + "/baseline";

        transport.on(url, FakeTransport.html(FakeTransport.page("Fees 100 EUR", "menu A"), Map.of("etag", "\"v1\"")));
        var first = refresh.runDue(T0);
        assertThat(first.baseline()).isEqualTo(1);
        var s = state(id);
        assertThat(s.contentHash()).hasSize(64);
        assertThat(s.etag()).isEqualTo("\"v1\"");
        assertThat(s.lastSuccessAt()).isEqualTo(T0);
        assertThat(s.nextCheckAt()).isEqualTo(T0.plus(Duration.ofDays(7))); // weekly policy
        String baselineHash = s.contentHash();

        // not due yet: nothing is fetched
        transport.clearRequests();
        assertThat(refresh.runDue(T0.plus(Duration.ofDays(1))).checked()).isZero();
        assertThat(transport.countFor(url)).isZero();

        // due, server says 304 Not Modified: conditional headers were sent, hash untouched
        transport.reset(url, FakeTransport.status(304));
        var notModified = refresh.runDue(T0.plus(Duration.ofDays(8)));
        assertThat(notModified.notModified()).isEqualTo(1);
        assertThat(transport.requests.stream().filter(r -> r.uri().toString().equals(url)).findFirst().orElseThrow()
                .headers()).containsEntry("If-None-Match", "\"v1\"");
        assertThat(state(id).contentHash()).isEqualTo(baselineHash);

        // only navigation/banner noise changed: not a change
        transport.reset(url, FakeTransport.html(FakeTransport.page("Fees 100 EUR", "a completely different menu")));
        assertThat(refresh.runDue(T0.plus(Duration.ofDays(16))).unchanged()).isEqualTo(1);
        assertThat(count("select count(*) from staged_change")).isZero();

        // the main content changed: reported once, for review (no adapter can say what changed)
        transport.reset(url, FakeTransport.html(FakeTransport.page("Fees 120 EUR", "menu")));
        var changed = refresh.runDue(T0.plus(Duration.ofDays(24)));
        assertThat(changed.changed()).isEqualTo(1);
        assertThat(changed.pendingReview()).isEqualTo(1);
        assertThat(state(id).lastChangeAt()).isEqualTo(T0.plus(Duration.ofDays(24)));
        assertThat(count("select count(*) from staged_change where fact_type = 'SOURCE_CONTENT' and status = 'PENDING_REVIEW'")).isEqualTo(1);

        // the same page again: no new review item
        assertThat(refresh.runDue(T0.plus(Duration.ofDays(32))).unchanged()).isEqualTo(1);
        assertThat(count("select count(*) from staged_change")).isEqualTo(1);

        // a further change supersedes the open item instead of piling up
        transport.reset(url, FakeTransport.html(FakeTransport.page("Fees 130 EUR", "menu")));
        refresh.runDue(T0.plus(Duration.ofDays(40)));
        assertThat(count("select count(*) from staged_change where status = 'PENDING_REVIEW'")).isEqualTo(1);
        assertThat(count("select count(*) from staged_change where status = 'SUPERSEDED'")).isEqualTo(1);
        // audit trail of every attempt
        assertThat(count("select count(*) from source_check where source_id = ?", id)).isEqualTo(6);
    }

    @Test
    void sourceDowntimeNeverTouchesPublishedFactsAndRecovers() {
        jdbc.update("update degree_program set duration_months = 24 where id = ?", programId);
        long id = monitor("/downtime", "content-hash", "CONTENT_HASH");
        String url = HOST + "/downtime";
        transport.on(url, FakeTransport.html(FakeTransport.page("Stable", "menu")));
        refresh.runDue(T0);

        transport.reset(url, FakeTransport.status(500));
        var failed = refresh.runDue(T0.plus(Duration.ofDays(8)));
        assertThat(failed.failed()).isEqualTo(1);
        var s = state(id);
        assertThat(s.failureCount()).isEqualTo(1);
        assertThat(s.status()).isEqualTo("ACTIVE");
        assertThat(s.nextCheckAt()).isEqualTo(T0.plus(Duration.ofDays(8)).plus(Duration.ofHours(6))); // back-off, not a week
        assertThat(s.contentHash()).isNotNull(); // the last verified baseline is preserved
        assertThat(count("select duration_months from degree_program where id = ?", programId)).isEqualTo(24);
        assertThat(count("select count(*) from staged_change")).isZero();

        // a 404 is a failure, never a verdict that the programme was discontinued
        transport.reset(url, FakeTransport.status(404));
        Instant t = T0.plus(Duration.ofDays(8)).plus(Duration.ofHours(7));
        refresh.runDue(t);
        refresh.runDue(t.plus(Duration.ofDays(1)));
        var failing = state(id);
        assertThat(failing.failureCount()).isEqualTo(3);
        assertThat(failing.status()).isEqualTo("FAILING");
        assertThat(jdbc.queryForObject("select error from source_check where source_id = ? order by id desc limit 1", String.class, id))
                .isEqualTo("HTTP 404");
        assertThat(count("select count(*) from degree_program where id = ?", programId)).isEqualTo(1);

        // recovery clears the failure state
        transport.reset(url, FakeTransport.html(FakeTransport.page("Stable", "menu")));
        refresh.runDue(t.plus(Duration.ofDays(10)));
        var recovered = state(id);
        assertThat(recovered.failureCount()).isZero();
        assertThat(recovered.status()).isEqualTo("ACTIVE");
    }

    @Test
    void anAccessDeniedOrChallengedSourceBecomesUnsupportedAndIsNotRetried() {
        long denied = monitor("/denied", "content-hash", "CONTENT_HASH");
        long challenge = monitor("/challenge", "content-hash", "CONTENT_HASH");
        transport.on(HOST + "/denied", FakeTransport.status(403));
        transport.on(HOST + "/challenge", FakeTransport.html("<html><head><title>Just a moment...</title></head></html>"));
        var summary = refresh.runDue(T0);
        assertThat(summary.blocked()).isEqualTo(2);
        assertThat(state(denied).status()).isEqualTo("UNSUPPORTED");
        assertThat(state(denied).unsupportedReason()).contains("not bypassed");
        assertThat(state(challenge).unsupportedReason()).contains("challenge");
        transport.clearRequests();
        assertThat(refresh.runDue(T0.plus(Duration.ofDays(60))).checked()).isZero();
        assertThat(transport.requests).isEmpty();
    }

    @Test
    void robotsTxtDisallowMakesTheSourceUnsupported() {
        long id = monitor("/private/page", "content-hash", "CONTENT_HASH");
        // robots.txt is cached per host, so this test uses a host of its own
        jdbc.update("update source set url = 'https://robots.zz-refresh.example/private/page' where id = ?", id);
        jdbc.update("update source_monitor set domain = 'robots.zz-refresh.example' where source_id = ?", id);
        approvedHosts.refresh();
        transport.on("https://robots.zz-refresh.example/robots.txt", FakeTransport.text("text/plain", "User-agent: *\nDisallow: /private/\n"));
        assertThat(refresh.runDue(T0).blocked()).isEqualTo(1);
        assertThat(state(id).unsupportedReason()).contains("robots.txt disallows");
        assertThat(transport.countFor("https://robots.zz-refresh.example/private/page")).isZero();
    }

    @Test
    void aHostThatResolvesToAnInternalAddressIsBlockedBySsrfProtection() {
        long id = monitor("/ignored", "content-hash", "CONTENT_HASH");
        jdbc.update("update source set url = 'https://internal.zz-refresh.example/admin' where id = ?", id);
        jdbc.update("update source_monitor set domain = 'internal.zz-refresh.example' where source_id = ?", id);
        approvedHosts.refresh();
        assertThat(refresh.runDue(T0).blocked()).isEqualTo(1);
        assertThat(state(id).unsupportedReason()).contains("private or internal");
        assertThat(transport.requests).isEmpty();
    }

    @Test
    void aRateLimitedSourceIsRescheduledAndNotCountedAsAFailure() {
        long id = monitor("/limited", "content-hash", "CONTENT_HASH");
        transport.on(HOST + "/limited", FakeTransport.status(429, "Retry-After", "7200"));
        assertThat(refresh.runDue(T0).rateLimited()).isEqualTo(1);
        var s = state(id);
        assertThat(s.nextCheckAt()).isEqualTo(T0.plus(Duration.ofHours(2)));
        assertThat(s.failureCount()).isZero();
        assertThat(s.status()).isEqualTo("ACTIVE");
    }

    @Test
    void structuredDataFillsEmptyFieldsAutomaticallyButNeverOverwritesAPublishedValue() {
        long id = monitor("/jsonld", "jsonld-program", "JSON_LD");
        String url = HOST + "/jsonld";
        String ld = "{\"@type\":\"Course\",\"timeToComplete\":\"P2Y\",\"inLanguage\":[\"en\",\"fi\"]}";
        transport.on(url, FakeTransport.html(FakeTransport.pageWithJsonLd("v1", ld)));
        var first = refresh.runDue(T0);
        assertThat(first.autoApplied()).isEqualTo(2);
        assertThat(count("select duration_months from degree_program where id = ?", programId)).isEqualTo(24);
        assertThat(jdbc.queryForList("select language_code from program_language where program_id = ? order by 1", String.class, programId))
                .containsExactly("en", "fi");
        assertThat(count("select count(*) from staged_change where status = 'AUTO_APPLIED' and decided_by = 'system:auto'")).isEqualTo(2);

        // the page text changes but the structured data does not: nothing new is staged
        transport.reset(url, FakeTransport.html(FakeTransport.pageWithJsonLd("v2 text only", ld)));
        refresh.runDue(T0.plus(Duration.ofDays(8)));
        assertThat(count("select count(*) from staged_change")).isEqualTo(2);

        // the page now claims a different duration: it conflicts with the published value, so it waits for review
        String changed = "{\"@type\":\"Course\",\"timeToComplete\":\"P3Y\",\"inLanguage\":[\"en\",\"fi\"]}";
        transport.reset(url, FakeTransport.html(FakeTransport.pageWithJsonLd("v3", changed)));
        var conflict = refresh.runDue(T0.plus(Duration.ofDays(16)));
        assertThat(conflict.pendingReview()).isEqualTo(1);
        assertThat(conflict.autoApplied()).isZero();
        assertThat(count("select duration_months from degree_program where id = ?", programId)).isEqualTo(24); // untouched
        var pending = review.pending();
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).oldValue()).isEqualTo("24");
        assertThat(pending.get(0).reason()).contains("differs from the published value");

        // a reviewer approves: only then does the value change
        review.approve(pending.get(0).id(), "reviewer");
        assertThat(count("select duration_months from degree_program where id = ?", programId)).isEqualTo(36);
        assertThat(jdbc.queryForObject("select status from staged_change where id = ?", String.class, pending.get(0).id())).isEqualTo("APPROVED");
        assertThat(state(id).lastResult()).isEqualTo("CHANGED");
    }

    @Test
    void structuredDataThatBreaksValidationIsRejectedAndPublishesNothing() {
        monitor("/badld", "jsonld-program", "JSON_LD");
        transport.on(HOST + "/badld", FakeTransport.html(FakeTransport.pageWithJsonLd("x",
                "{\"@type\":\"Course\",\"timeToComplete\":\"P200Y\",\"inLanguage\":\"English\"}")));
        var summary = refresh.runDue(T0);
        assertThat(summary.autoApplied()).isZero();
        assertThat(count("select count(*) from staged_change")).isZero();
        assertThat(count("select count(*) from degree_program where duration_months is not null")).isZero();
    }

    @Test
    void aNewParserVersionRebaselinesTheHashInsteadOfReportingEveryPageAsChanged() {
        long id = monitor("/parser", "content-hash", "CONTENT_HASH");
        transport.on(HOST + "/parser", FakeTransport.html(FakeTransport.page("Stable text", "menu")));
        refresh.runDue(T0);
        jdbc.update("update source_monitor set hash_parser_version = 'canon-0+content-hash@1' where source_id = ?", id);

        var summary = refresh.runDue(T0.plus(Duration.ofDays(8)));
        assertThat(summary.baseline()).isEqualTo(1);
        assertThat(summary.changed()).isZero();
        assertThat(count("select count(*) from staged_change")).isZero();
        assertThat(state(id).hashParserVersion()).startsWith("canon-1+content-hash@");
    }

    @Test
    void anAdapterErrorRollsBackEverythingAndIsRecordedAsAFailure() {
        long id = monitor("/boom", "boom", "ADAPTER");
        transport.on(HOST + "/boom", FakeTransport.html(FakeTransport.page("anything", "menu")));
        var summary = refresh.runDue(T0);
        assertThat(summary.failed()).isEqualTo(1);
        var s = state(id);
        assertThat(s.contentHash()).isNull(); // no half-applied baseline
        assertThat(s.failureCount()).isEqualTo(1);
        assertThat(count("select count(*) from staged_change")).isZero();
        assertThat(jdbc.queryForObject("select error from source_check where source_id = ?", String.class, id)).contains("adapter bug");
    }

    @Test
    void anApproachingVerifiedDeadlineSwitchesTheSourceToDailyChecks() {
        long id = monitor("/deadline", "content-hash", "CONTENT_HASH");
        LocalDate in10Days = LocalDate.ofInstant(T0, ZoneOffset.UTC).plusDays(10);
        jdbc.update("insert into program_intake (program_id, source_id, label, application_deadline) values (?, ?, 'Autumn', ?)",
                programId, id, in10Days);
        transport.on(HOST + "/deadline", FakeTransport.html(FakeTransport.page("Apply soon", "menu")));
        refresh.runDue(T0);
        assertThat(state(id).nextCheckAt()).isEqualTo(T0.plus(Duration.ofDays(1)));
    }

    @Test
    void runningTheSameInstantTwiceChecksEachSourceOnce() {
        monitor("/twice", "content-hash", "CONTENT_HASH");
        transport.on(HOST + "/twice", FakeTransport.html(FakeTransport.page("once", "menu")));
        assertThat(refresh.runDue(T0).checked()).isEqualTo(1);
        assertThat(refresh.runDue(T0).checked()).isZero();
        assertThat(transport.countFor(HOST + "/twice")).isEqualTo(1);
    }

    @Test
    void oneFailingSourceDoesNotStopTheOthers() {
        long bad = monitor("/mixed-bad", "boom", "ADAPTER");
        long good = monitor("/mixed-good", "content-hash", "CONTENT_HASH");
        transport.on(HOST + "/mixed-bad", FakeTransport.html(FakeTransport.page("x", "m")));
        transport.on(HOST + "/mixed-good", FakeTransport.html(FakeTransport.page("y", "m")));
        var summary = refresh.runDue(T0);
        assertThat(summary.checked()).isEqualTo(2);
        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.baseline()).isEqualTo(1);
        assertThat(state(good).contentHash()).isNotNull();
        assertThat(state(bad).contentHash()).isNull();
    }

    @Test
    void aHomePageThatChangesIsRecordedButRaisesNoReviewItem() {
        // seen against live sites: a university home page changed within minutes (news), which is not a fact change
        long id = monitor("/home-watch", "content-watch", "CONTENT_HASH");
        transport.on(HOST + "/home-watch", FakeTransport.html(FakeTransport.page("News: open day on Monday", "menu")));
        refresh.runDue(T0);
        transport.reset(HOST + "/home-watch", FakeTransport.html(FakeTransport.page("News: new rector appointed", "menu")));
        var summary = refresh.runDue(T0.plus(Duration.ofDays(8)));
        assertThat(summary.changed()).isEqualTo(1);
        assertThat(summary.pendingReview()).isZero();
        assertThat(count("select count(*) from staged_change")).isZero();
        assertThat(state(id).lastResult()).isEqualTo("CHANGED"); // still visible in the audit trail and the report
        assertThat(report.generate(T0.plus(Duration.ofDays(8))).changedPages7d()).isEqualTo(1);
    }

    @Test
    void theReportCountsMonitoredSourcesChecksAndCountries() {
        long home = monitor("/", "content-watch", "CONTENT_HASH");
        long fees = monitor("/tuition-fees", "content-hash", "CONTENT_HASH");
        transport.on(HOST + "/", FakeTransport.html(FakeTransport.page("home", "m")));
        transport.on(HOST + "/tuition-fees", FakeTransport.html(FakeTransport.page("fees", "m")));
        refresh.runDue(T0);
        assertThat(state(home).lastSuccessAt()).isEqualTo(T0);
        assertThat(state(fees).lastSuccessAt()).isEqualTo(T0);
        var r = report.generate(T0.plus(Duration.ofHours(1)));
        assertThat(r.monitoredSources()).isEqualTo(2);
        assertThat(r.successfulChecks7d()).isEqualTo(2);
        assertThat(r.countries()).anySatisfy(c -> {
            assertThat(c.iso()).isEqualTo("ZR");
            assertThat(c.universities()).isEqualTo(1);
            assertThat(c.verified()).isEqualTo(1);
            assertThat(c.programs()).isEqualTo(1);
        });
        assertThat(r.render()).contains("MONITORING REPORT").contains("ZR");
    }

    @Test
    void theReportListsUnsupportedSourcesAsUnresolved() {
        monitor("/denied-report", "content-hash", "CONTENT_HASH");
        transport.on(HOST + "/denied-report", FakeTransport.status(403));
        refresh.runDue(T0);
        var r = report.generate(T0);
        assertThat(r.failedChecks7d()).isEqualTo(1);
        assertThat(r.monitorsByStatus()).containsEntry("UNSUPPORTED", 1);
        assertThat(r.staleOrUnresolved()).anyMatch(s -> s.contains("/denied-report") && s.contains("UNSUPPORTED"));
        assertThat(r.changesAwaitingReview()).isZero();
    }
}

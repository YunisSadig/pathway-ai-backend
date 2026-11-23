package com.pathwayai.backend.provider.polon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pathwayai.backend.TestcontainersConfiguration;
import com.pathwayai.backend.provider.studyinfo.FieldTaxonomy;
import com.pathwayai.backend.refresh.RefreshProperties;
import com.pathwayai.backend.refresh.fetch.ApprovedHosts;
import com.pathwayai.backend.refresh.fetch.PageFetcher;
import com.pathwayai.backend.refresh.pipeline.ChangePipeline;
import com.pathwayai.backend.refresh.pipeline.ProgramFacts;
import com.pathwayai.backend.refresh.registry.SourceMonitorRepository;
import com.pathwayai.backend.refresh.support.FakeTransport;
import com.pathwayai.backend.refresh.support.RefreshTestConfig;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

/** Preview and apply against PostgreSQL with recorded POL-on responses and a faked network; Finland data is never touched. */
@SpringBootTest(properties = {"app.refresh.min-host-interval-ms=0", "app.refresh.extra-approved-hosts=radon.nauka.gov.pl,www.international.agh.edu.pl,irk.uw.edu.pl,rekrutacja.uw.edu.pl,put.poznan.pl,apply.p.lodz.pl"})
@Import({TestcontainersConfiguration.class, RefreshTestConfig.class})
@Transactional
class PolandCuratedIT {
    @Autowired PolonSources sources;
    @Autowired PageFetcher fetcher;
    @Autowired FieldTaxonomy taxonomy;
    @Autowired JdbcClient jdbc;
    @Autowired SourceMonitorRepository monitors;
    @Autowired ChangePipeline pipeline;
    @Autowired ProgramFacts programFacts;
    @Autowired ApprovedHosts approvedHosts;
    @Autowired RefreshProperties props;
    @Autowired Clock clock;
    @Autowired PlatformTransactionManager tx;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired FakeTransport transport;

    @BeforeEach
    void seed() throws Exception {
        PolandCuratedCli.retryPauseMs = 0; // unscripted fake pages fail; do not wait between attempts
        jdbcTemplate.update("insert into country(iso2_code,name) values('PL','Synthetic Poland') on conflict do nothing");
        try (var in = new org.springframework.core.io.ClassPathResource("reference-data/studyinfo-field-map.json").getInputStream()) {
            for (var m : tools.jackson.databind.json.JsonMapper.builder().build().readTree(in).path("mappings")) // fields exist in production from the taxonomy import
                jdbcTemplate.update("insert into academic_field(slug,name) values(?,?) on conflict do nothing", m.path("slug").asText(), m.path("slug").asText());
        }
        for (String slug : sources.candidates().stream().map(PolonSources.Candidate::university).distinct().toList())
            jdbcTemplate.update("insert into university(country_id,slug,name) select id,?,? from country where iso2_code='PL'", slug, "Synthetic " + slug);
        for (var c : sources.candidates()) {
            transport.reset(PolonSources.recordUrl(c.courseUuid()), FakeTransport.text("application/json", PolonParserTest.fixture(c.courseUuid())));
            if (c.programUrl() != null) transport.reset(c.programUrl(), FakeTransport.text("text/html", "<html><title>Synthetic page</title></html>"));
            if (c.feesUrl() != null) transport.reset(c.feesUrl(), FakeTransport.text("text/html", "<html><title>Synthetic fees</title></html>"));
        }
    }

    PolandCuratedCli cli(String command, String fingerprint) {
        return new PolandCuratedCli(sources, fetcher, taxonomy, jdbc, monitors, pipeline, programFacts, approvedHosts, props, clock, tx, command, fingerprint);
    }

    long count(String sql) { return jdbcTemplate.queryForObject(sql, Long.class); }

    @Test
    void previewWritesNothingAndSkipsExactlyTheUnconfirmedIdentities() {
        var plan = cli("preview", "").plan();
        assertThat(plan.verified()).hasSize(14);
        assertThat(plan.skipped()).hasSize(6).allMatch(s -> s.reason().contains("unconfirmed"));
        assertThat(plan.verified().stream().filter(v -> v.candidate().programUrl() == null).map(v -> v.pageStatus()))
                .singleElement().asString().startsWith("NOT STATED");
        assertThat(plan.verified()).allSatisfy(v -> assertThat(v.existingId()).isNull());
        cli("preview", "").run(new DefaultApplicationArguments());
        assertThat(count("select count(*) from degree_program")).isZero();
        assertThat(count("select count(*) from catalog_release")).isZero();
    }

    @Test
    void applyNeedsTheApprovedFingerprintThenCreatesTheProgrammesOnceAndNeverFabricatesFees() {
        String fingerprint = cli("preview", "").plan().fingerprint();
        assertThatThrownBy(() -> cli("apply", "wrong").run(new DefaultApplicationArguments())).hasMessageContaining("fingerprint");
        assertThat(count("select count(*) from degree_program")).isZero();

        cli("apply", fingerprint).run(new DefaultApplicationArguments());
        assertThat(count("select count(*) from degree_program")).isEqualTo(14);
        assertThat(count("select count(*) from catalog_membership where release_id='poland-curated-v1'")).isEqualTo(14);
        assertThat(count("select count(*) from catalog_release where id='poland-curated-v1' and enabled and country_code='PL'")).isEqualTo(1);
        assertThat(count("select count(*) from program_external_id where scheme='POLON_COURSE'")).isEqualTo(14);
        assertThat(count("select count(*) from source_monitor where adapter_name='polon-courses' and status='ACTIVE'")).isEqualTo(14);
        assertThat(count("select count(*) from degree_program where verification_status='VERIFIED' and official_application_url is not null")).isZero();
        assertThat(count("select count(*) from tuition_fee")).as("POL-on states no fee").isZero();
        assertThat(count("select count(*) from degree_program where degree_level='BACHELOR'")).isEqualTo(7);
        assertThat(count("select count(*) from degree_program where degree_level='MASTER'")).isEqualTo(7);

        cli("apply", fingerprint).run(new DefaultApplicationArguments()); // idempotent
        assertThat(count("select count(*) from degree_program")).isEqualTo(14);
        assertThat(count("select count(*) from program_external_id where scheme='POLON_COURSE'")).isEqualTo(14);
    }


    UwFeePdfCli pdfCli(String command, String fingerprint) {
        return new UwFeePdfCli(sources, fetcher, jdbc, clock, command, fingerprint);
    }

    @Test
    void uwFeePdfRowsAreOnlyEverQueuedForReviewNeverPublishedAndStagingIsIdempotent() throws Exception {
        cli("apply", cli("preview", "").plan().fingerprint()).run(new DefaultApplicationArguments());
        transport.reset(UwFeePdfCli.PDF_URL, new com.pathwayai.backend.refresh.fetch.HttpTransport.Response(200,
                java.util.Map.of("content-type", "application/pdf"), UwFeePdfTest.pdf(), false));
        long tuitionBefore = count("select count(*) from tuition_fee");
        var out = new java.io.ByteArrayOutputStream();
        var saved = System.out;
        System.setOut(new java.io.PrintStream(out, true, java.nio.charset.StandardCharsets.UTF_8));
        try { pdfCli("uw-fees-pdf-preview", "").run(new DefaultApplicationArguments()); } finally { System.setOut(saved); }
        String text = out.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(text).contains("UWPDF_PREVIEW no writes").contains("items=1");
        assertThat(count("select count(*) from staged_change where parser_version like 'uw-fees-pdf%'")).as("preview writes nothing").isZero();
        String fingerprint = text.replaceAll("(?s).*fingerprint=([0-9a-f]{64}).*", "$1").trim();
        assertThatThrownBy(() -> pdfCli("uw-fees-pdf-stage", "wrong").run(new DefaultApplicationArguments())).hasMessageContaining("fingerprint");

        pdfCli("uw-fees-pdf-stage", fingerprint).run(new DefaultApplicationArguments());
        pdfCli("uw-fees-pdf-stage", fingerprint).run(new DefaultApplicationArguments()); // idempotent
        assertThat(count("select count(*) from staged_change where fact_type='TUITION'")).isEqualTo(1);
        assertThat(count("select count(*) from staged_change where parser_version like 'uw-fees-pdf%' and status='PENDING_REVIEW' and risk='HIGH' and decided_at is null and decided_by is null")).isEqualTo(1);
        assertThat(count("select count(*) from staged_change where reason like '%PDF page 2, exact row text%3,300 EUR%'")).isEqualTo(1);
        assertThat(count("select count(*) from tuition_fee")).as("nothing is published").isEqualTo(tuitionBefore);
        assertThat(count("select count(*) from source_monitor m join source s on s.id=m.source_id where s.url like '%.pdf'")).as("no monitor reads the PDF").isZero();
    }

    String slugs(String university) {
        return String.join(",", jdbcTemplate.queryForList("select p.slug from degree_program p join university u on u.id=p.university_id where u.slug=? order by p.slug", String.class, university));
    }

    @Test
    void aSameNamePairGetsTheDegreeLevelAsSuffixOnBothNeverANumber() {
        cli("apply", cli("preview", "").plan().fingerprint()).run(new DefaultApplicationArguments());
        assertThat(slugs("politechnika-poznanska")).isEqualTo("artificial-intelligence-bachelor,artificial-intelligence-master");
        assertThat(slugs("akademia-gorniczo-hutnicza")).isEqualTo("computer-science"); // a unique name keeps its plain slug
    }

    @Test
    void anOldNumberedSlugIsRenamedWithAnArchiveRowUnlessSomeoneSavedIt() {
        cli("apply", cli("preview", "").plan().fingerprint()).run(new DefaultApplicationArguments());
        jdbcTemplate.update("update degree_program set slug='artificial-intelligence' where slug='artificial-intelligence-bachelor'");
        jdbcTemplate.update("update degree_program set slug='artificial-intelligence-32400' where slug='artificial-intelligence-master'");
        jdbcTemplate.update("insert into user_account (email, email_normalized, password_hash) values ('s@example.test','s@example.test','x')");
        jdbcTemplate.update("insert into saved_program(account_id, program_identifier) select id, 'politechnika-poznanska--artificial-intelligence-32400' from user_account where email='s@example.test'");
        var plan = cli("preview", "").plan();
        assertThatThrownBy(() -> cli("apply", plan.fingerprint()).run(new DefaultApplicationArguments())).hasMessageContaining("saved item");
        assertThat(slugs("politechnika-poznanska")).isEqualTo("artificial-intelligence,artificial-intelligence-32400"); // refused: nothing changed
        jdbcTemplate.update("delete from saved_program");
        cli("apply", plan.fingerprint()).run(new DefaultApplicationArguments());
        assertThat(slugs("politechnika-poznanska")).isEqualTo("artificial-intelligence-bachelor,artificial-intelligence-master");
        assertThat(count("select count(*) from program_fact_history where field_name='slug'")).isEqualTo(2);
        assertThat(count("select count(*) from degree_program")).isEqualTo(14);
    }

    @Test
    void recordedOfficialPagesGiveVerifiedLinksAndTheOneUnambiguousFeeThroughThePipeline() throws Exception {
        var agh = sources.candidates().stream().filter(c -> c.key().equals("akademia-computer-science-bachelor")).findFirst().orElseThrow();
        transport.reset(agh.programUrl(), FakeTransport.text("text/html", PolishPageAdapterTest.page("agh-programme.html")));
        var put = sources.candidates().stream().filter(c -> c.key().equals("politechnika-sztuczna-inteligencja-artificial-intelligence-bachelor")).findFirst().orElseThrow();
        transport.reset(put.feesUrl(), FakeTransport.text("text/html", PolishPageAdapterTest.page("put-first-cycle-fees.html")));
        var ml = sources.candidates().stream().filter(c -> c.key().equals("uniwersytet-machine-learning-master")).findFirst().orElseThrow();
        transport.reset(ml.programUrl(), FakeTransport.text("text/html", PolishPageAdapterTest.page("uw-irk-ml-programme.html")));
        var tul = sources.candidates().stream().filter(c -> c.key().equals("politechnika-computer-science-bachelor")).findFirst().orElseThrow();
        transport.reset(tul.feesUrl(), FakeTransport.text("text/html", PolishPageAdapterTest.page("tul-fees.html")));
        transport.reset(agh.admissionsUrl(), FakeTransport.text("text/html", PolishPageAdapterTest.page("agh-recruitment.html")));
        for (var x : agh.extras()) {
            String fixture = x.url().contains("deadlines") ? "agh-deadlines-bachelor.html" : x.url().contains("rules") ? "agh-rules-bachelor.html" : "agh-documents-bachelor.html";
            transport.reset(x.url(), FakeTransport.text("text/html", PolishPageAdapterTest.page(fixture)));
        }
        transport.reset(agh.feesUrl(), FakeTransport.text("text/html", PolishPageAdapterTest.page("agh-fees.html")));
        String fingerprint = cli("preview", "").plan().fingerprint();
        cli("apply", fingerprint).run(new DefaultApplicationArguments());
        long program = jdbcTemplate.queryForObject("select e.program_id from program_external_id e where e.scheme='POLON_COURSE' and e.value=?", Long.class, agh.courseUuid());
        assertThat(jdbcTemplate.queryForObject("select official_program_url from degree_program where id=?", String.class, program)).as("AGH's page is an offer list").isNull();
        assertThat(jdbcTemplate.queryForObject("select official_admissions_url from degree_program where id=?", String.class, program)).isEqualTo(agh.admissionsUrl());
        assertThat(jdbcTemplate.queryForObject("select value from program_university_fact where program_id=? and field_name='selection'", String.class, program)).contains("300 points");
        assertThat(jdbcTemplate.queryForObject("select academic_year from program_university_fact where program_id=? and field_name='application_period' and scope_key='2027-2028'", String.class, program))
                .as("the bachelor deadlines page prints the year").isEqualTo("2027-2028");
        long mlProgram = jdbcTemplate.queryForObject("select e.program_id from program_external_id e where e.scheme='POLON_COURSE' and e.value=?", Long.class, ml.courseUuid());
        assertThat(jdbcTemplate.queryForObject("select official_program_url from degree_program where id=?", String.class, mlProgram)).isEqualTo(ml.programUrl());
        assertThat(jdbcTemplate.queryForObject("select value from program_university_fact where program_id=? and field_name='financial_url'", String.class, program)).isEqualTo(agh.feesUrl());
        assertThat(jdbcTemplate.queryForObject("select value from program_university_fact where program_id=? and field_name='qualification'", String.class, mlProgram)).isEqualTo("Magister (MSc)");
        assertThat(jdbcTemplate.queryForObject("select academic_year from program_university_fact where program_id=? and field_name='application_period'", String.class, mlProgram))
                .as("the year is the one the page prints").isEqualTo("2026-2027");
        assertThat(jdbcTemplate.queryForObject("select value from program_university_fact where program_id=? and field_name='duration_structure'", String.class, program)).isEqualTo("7 semesters (3,5 years)");
        assertThat(jdbcTemplate.queryForObject("select academic_year from program_university_fact where program_id=? and field_name='application_period' and scope_key='year-not-stated'", String.class, program))
                .as("AGH prints no year, so none is stored").isNull();
        long putProgram = jdbcTemplate.queryForObject("select e.program_id from program_external_id e where e.scheme='POLON_COURSE' and e.value=?", Long.class, put.courseUuid());
        assertThat(jdbcTemplate.queryForObject("select academic_year from program_university_fact where program_id=? and field_name='tuition_scope'", String.class, putProgram)).isEqualTo("2026-2027");
        assertThat(jdbcTemplate.queryForObject("select value from program_university_fact where program_id=? and field_name='tuition_scope'", String.class, putProgram)).contains("EUR").contains("PLN");
        assertThat(count("select count(*) from tuition_fee")).as("AGH and the three TUL programmes that share one fees page").isEqualTo(4);
        var fee = jdbcTemplate.queryForMap("select amount, currency, billing_period, student_category from tuition_fee where program_id=" + program);
        assertThat(fee.get("amount").toString()).startsWith("3000");
        assertThat(fee).containsEntry("currency", "EUR").containsEntry("billing_period", "PER_SEMESTER").containsEntry("student_category", "INTERNATIONAL");
        assertThat(count("select count(*) from source_monitor where adapter_name='pl-university-page' and status='ACTIVE'")).isGreaterThanOrEqualTo(2);
        assertThat(count("select count(*) from source s join program_source ps on ps.source_id=s.id where ps.program_id=" + program + " and s.source_type='UNIVERSITY_WEBSITE'")).isGreaterThanOrEqualTo(1);
        cli("apply", fingerprint).run(new DefaultApplicationArguments()); // idempotent
        assertThat(count("select count(*) from tuition_fee")).isEqualTo(4);
        assertThat(count("select count(*) from staged_change where status='PENDING_REVIEW'")).isZero();
    }

    @Test
    void anUnreadableUniversityPageNeverBlocksAProgrammeButIsNotRecordedAsVerified() {
        var c = sources.candidates().stream().filter(x -> x.identityConfirmed() && x.programUrl() != null).findFirst().orElseThrow();
        transport.reset(c.programUrl(), FakeTransport.status(404));
        var plan = cli("preview", "").plan();
        var v = plan.verified().stream().filter(x -> x.candidate().key().equals(c.key())).findFirst().orElseThrow();
        assertThat(v.pageStatus()).startsWith("NOT VERIFIED");
    }
}

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
@SpringBootTest(properties = {"app.refresh.min-host-interval-ms=0", "app.refresh.extra-approved-hosts=radon.nauka.gov.pl,www.international.agh.edu.pl,irk.uw.edu.pl,put.poznan.pl"})
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
        transport.reset(agh.feesUrl(), FakeTransport.text("text/html", PolishPageAdapterTest.page("agh-fees.html")));
        String fingerprint = cli("preview", "").plan().fingerprint();
        cli("apply", fingerprint).run(new DefaultApplicationArguments());
        long program = jdbcTemplate.queryForObject("select e.program_id from program_external_id e where e.scheme='POLON_COURSE' and e.value=?", Long.class, agh.courseUuid());
        assertThat(jdbcTemplate.queryForObject("select official_program_url from degree_program where id=?", String.class, program)).as("AGH's page is an offer list").isNull();
        long mlProgram = jdbcTemplate.queryForObject("select e.program_id from program_external_id e where e.scheme='POLON_COURSE' and e.value=?", Long.class, ml.courseUuid());
        assertThat(jdbcTemplate.queryForObject("select official_program_url from degree_program where id=?", String.class, mlProgram)).isEqualTo(ml.programUrl());
        assertThat(jdbcTemplate.queryForObject("select value from program_university_fact where program_id=? and field_name='financial_url'", String.class, program)).isEqualTo(agh.feesUrl());
        long putProgram = jdbcTemplate.queryForObject("select e.program_id from program_external_id e where e.scheme='POLON_COURSE' and e.value=?", Long.class, put.courseUuid());
        assertThat(jdbcTemplate.queryForObject("select academic_year from program_university_fact where program_id=? and field_name='tuition_scope'", String.class, putProgram)).isEqualTo("2026-2027");
        assertThat(jdbcTemplate.queryForObject("select value from program_university_fact where program_id=? and field_name='tuition_scope'", String.class, putProgram)).contains("EUR").contains("PLN");
        assertThat(count("select count(*) from tuition_fee")).as("only AGH states an unambiguous fee").isEqualTo(1);
        var fee = jdbcTemplate.queryForMap("select amount, currency, billing_period, student_category from tuition_fee where program_id=" + program);
        assertThat(fee.get("amount").toString()).startsWith("3000");
        assertThat(fee).containsEntry("currency", "EUR").containsEntry("billing_period", "PER_SEMESTER").containsEntry("student_category", "INTERNATIONAL");
        assertThat(count("select count(*) from source_monitor where adapter_name='pl-university-page' and status='ACTIVE'")).isGreaterThanOrEqualTo(2);
        assertThat(count("select count(*) from source s join program_source ps on ps.source_id=s.id where ps.program_id=" + program + " and s.source_type='UNIVERSITY_WEBSITE'")).isGreaterThanOrEqualTo(1);
        cli("apply", fingerprint).run(new DefaultApplicationArguments()); // idempotent
        assertThat(count("select count(*) from tuition_fee")).isEqualTo(1);
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

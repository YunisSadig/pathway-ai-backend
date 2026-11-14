package com.pathwayai.backend.provider.studyinfo;

import static org.assertj.core.api.Assertions.assertThat;

import com.pathwayai.backend.TestcontainersConfiguration;
import com.pathwayai.backend.refresh.pipeline.StagedChangeService;
import com.pathwayai.backend.refresh.registry.SourceMonitorRepository;
import com.pathwayai.backend.refresh.schedule.RefreshService;
import com.pathwayai.backend.refresh.support.FakeTransport;
import com.pathwayai.backend.refresh.support.RefreshTestConfig;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The official-API connector end to end against PostgreSQL: import, repeat import, refresh of an unchanged record,
 * and controlled changes. The provider is a scripted transport serving a real recorded Studyinfo response; the
 * modified responses are derived from it in code and are test inputs, not things the provider published.
 */
@SpringBootTest(properties = {"app.refresh.min-host-interval-ms=0", "app.studyinfo.config=classpath:studyinfo/test-config.json"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RefreshTestConfig.class, StudyinfoConnectorIT.Taxonomy.class})
class StudyinfoConnectorIT {

    /** Two codes are mapped here so a field change can be demonstrated (the production file maps only reviewed codes). */
    @TestConfiguration(proxyBeanMethods = false)
    static class Taxonomy {
        @Bean
        @Primary
        FieldTaxonomy testTaxonomy() {
            return FieldTaxonomy.of(Map.of(
                    "061", new FieldTaxonomy.Entry("061", "computer-and-data-science", "Information and Communication Technologies (ICT)"),
                    "054", new FieldTaxonomy.Entry("054", "mathematics-and-statistics", "Mathematics and statistics")));
        }
    }

    static final String KOULUTUS = "1.2.246.562.13.00000000000000000973";
    static final String TOTEUTUS = "1.2.246.562.17.00000000000000001637";
    static final String TARJONTA = "https://opintopolku.fi/konfo-backend/external/search/oppilaitos/1.2.246.562.10.38417217288/tarjonta"
            + "?lng=en&order=asc&size=100&page=1";
    static final Instant T1 = Instant.parse("2026-11-20T00:00:00Z");

    @Autowired StudyinfoImporter importer;
    @Autowired RefreshService refresh;
    @Autowired SourceMonitorRepository monitors;
    @Autowired StagedChangeService review;
    @Autowired FakeTransport transport;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mockMvc;

    String record;

    @BeforeEach
    void seed() {
        transport.clearRequests();
        jdbc.update("insert into country (iso2_code, name) values ('ZS', 'Studyland') on conflict do nothing");
        jdbc.update("insert into academic_field (slug, name) values ('computer-and-data-science', 'Computer and Data Science') on conflict do nothing");
        jdbc.update("insert into academic_field (slug, name) values ('mathematics-and-statistics', 'Mathematics and Statistics') on conflict do nothing");
        record = importer.recordUrl(KOULUTUS);
        transport.reset(TARJONTA, FakeTransport.text("application/json", """
                {"total":1,"hits":[{"koulutustyyppi":"yo","koulutusOid":"%s","toteutusOid":"%s",
                 "opetuskielet":["oppilaitoksenopetuskieli_4"],
                 "nimi":{"en":"Computer Science and Engineering, Master of Science (Technology) (2 years)"}}]}
                """.formatted(KOULUTUS, TOTEUTUS)));
        serve(StudyinfoParserTest.recorded());
    }

    @AfterEach
    void clean() {
        jdbc.update("delete from staged_change");
        jdbc.update("delete from source_check");
        jdbc.update("delete from source_monitor");
        jdbc.update("delete from university where slug = 'zz-studyinfo-u'");
        jdbc.update("delete from source where url like 'https://opintopolku.fi/%'");
        jdbc.update("delete from country where iso2_code = 'ZS'");
    }

    void serve(String json) {
        transport.reset(record, FakeTransport.text("application/json;charset=utf-8", json));
    }

    void serveModified(Consumer<Map<String, Object>> change) {
        serve(StudyinfoParserTest.modified(change));
    }

    long programId() {
        return jdbc.queryForObject("select program_id from program_external_id where scheme = 'STUDYINFO_KOULUTUS_OID' and value = ?",
                Long.class, KOULUTUS);
    }

    /** Everything that would change if an unchanged record were rewritten. */
    Map<String, Object> snapshot() {
        long p = programId();
        return Map.of(
                "rowVersion", jdbc.queryForObject("select xmin::text from degree_program where id = ?", String.class, p),
                "updatedAt", jdbc.queryForObject("select updated_at::text from degree_program where id = ?", String.class, p),
                "provenance", jdbc.queryForList("select field_name || '@' || observed_at::text from program_field_provenance where program_id = ? order by 1", String.class, p),
                "staged", jdbc.queryForObject("select count(*) from staged_change", Long.class),
                "history", jdbc.queryForObject("select count(*) from program_fact_history", Long.class),
                "intakes", jdbc.queryForList("select id::text || ':' || coalesce(application_deadline::text, '') from program_intake where program_id = ? order by id", String.class, p),
                "languages", jdbc.queryForList("select language_code from program_language where program_id = ?", String.class, p));
    }

    String field(String column) {
        return jdbc.queryForObject("select " + column + " from degree_program p left join academic_field f on f.id = p.academic_field_id where p.id = ?",
                String.class, programId());
    }

    @Test
    void importCreatesTheProgramFromTheOfficialRecordWithProvenanceAndAMonitor() {
        var summary = importer.run(false, null, null);

        assertThat(summary.created()).isEqualTo(1);
        assertThat(summary.failed()).isZero();
        assertThat(field("p.name")).isEqualTo("Computer Science and Engineering, Master of Science (Technology) (2 years)");
        assertThat(field("p.degree_level")).isEqualTo("MASTER");
        assertThat(field("p.duration_months")).isEqualTo("24");
        assertThat(field("p.campus_city")).isEqualTo("Oulu");
        assertThat(field("f.slug")).isEqualTo("computer-and-data-science");
        assertThat(field("p.verification_status")).isEqualTo("VERIFIED");
        assertThat(field("p.official_program_url")).isEqualTo("https://opintopolku.fi/konfo/en/toteutus/" + TOTEUTUS);
        assertThat(field("p.official_application_url")).as("no application URL is stated by the API").isNull();
        assertThat(jdbc.queryForList("select language_code from program_language where program_id = ?", String.class, programId())).containsExactly("en");
        assertThat(jdbc.queryForList("select application_deadline::text || ' ' || label from program_intake where program_id = ?", String.class, programId()))
                .containsExactly("2027-01-21 Joint Application to Higher Education, Spring 2027");

        // external identifiers and the university's own provider id
        assertThat(jdbc.queryForList("select scheme || '=' || value from program_external_id where program_id = ? order by 1", String.class, programId()))
                .containsExactly("STUDYINFO_KOULUTUS_OID=" + KOULUTUS, "STUDYINFO_TOTEUTUS_OID=" + TOTEUTUS);
        assertThat(jdbc.queryForObject("select verification_status from university where slug = 'zz-studyinfo-u'", String.class))
                .as("a university created from the provider alone is never presented as verified").isEqualTo("UNVERIFIED");
        assertThat(jdbc.queryForObject("select count(*) from university_external_id where scheme = 'STUDYINFO_ORG_OID'", Long.class)).isEqualTo(1);

        // field-level provenance: who stated each field, by what mechanism, when verified
        var rows = jdbc.queryForList("select field_name, mechanism, provider_ref, verified_on is not null as dated from program_field_provenance where program_id = ? order by 1", programId());
        assertThat(rows).extracting(r -> r.get("field_name")).containsExactly("academic_field", "campus_city", "degree_level", "duration_months", "languages", "name");
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.get("mechanism")).isEqualTo("OFFICIAL_API");
            assertThat(r.get("provider_ref")).isEqualTo(KOULUTUS);
            assertThat(r.get("dated")).isEqualTo(true);
        });

        // the source is the exact API record, typed as a government source, and it is monitored by the API adapter
        var src = jdbc.queryForMap("select s.source_type, s.publisher, m.adapter_name, m.extraction_method, m.last_result, m.status from source s join source_monitor m on m.source_id = s.id where s.url = ?", record);
        assertThat(src).containsEntry("source_type", "GOVERNMENT").containsEntry("adapter_name", "studyinfo-external")
                .containsEntry("extraction_method", "ADAPTER").containsEntry("last_result", "BASELINE").containsEntry("status", "ACTIVE");

        // the import is audited through the same staging table as every refresh: published facts, nothing pending
        assertThat(jdbc.queryForList("select distinct status from staged_change", String.class)).containsExactly("AUTO_APPLIED");
        assertThat(jdbc.queryForList("select reason from staged_change", String.class)).allMatch(r -> r.contains("initial import"));
    }

    @Test
    void importingTwiceCreatesNothingAndRewritesNothing() {
        importer.run(false, null, null);
        var before = snapshot();
        long requestsBefore = transport.countFor(record);

        var again = importer.run(false, null, null);

        assertThat(again.created()).isZero();
        assertThat(again.alreadyImported()).isEqualTo(1);
        assertThat(snapshot()).isEqualTo(before);
        assertThat(transport.countFor(record)).as("an already imported program is not even re-fetched by the importer").isEqualTo(requestsBefore);
        assertThat(jdbc.queryForObject("select count(*) from degree_program p join university u on u.id = p.university_id where u.slug = 'zz-studyinfo-u'", Long.class)).isEqualTo(1);
    }

    @Test
    void refreshingAnUnchangedRecordWritesNothingButRecordsTheSuccessfulCheck() {
        importer.run(false, null, null);
        var before = snapshot();

        monitors.markDue(StudyinfoAdapter.NAME, T1);
        var run = refresh.runDueForAdapter(StudyinfoAdapter.NAME, T1);

        assertThat(run.checked()).isEqualTo(1);
        assertThat(run.unchanged()).isEqualTo(1);
        assertThat(run.pendingReview()).isZero();
        assertThat(snapshot()).as("published facts and their provenance are untouched").isEqualTo(before);
        var m = monitors.findBySourceId(jdbc.queryForObject("select id from source where url = ?", Long.class, record)).orElseThrow();
        assertThat(m.lastSuccessAt()).as("last successful refresh is recorded").isEqualTo(T1);
        assertThat(m.failureCount()).isZero();
    }

    @Test
    void aReindexedRecordWithIdenticalFactsStillWritesNothing() {
        importer.run(false, null, null);
        var before = snapshot();

        // the provider's volatile fields change, so the content hash changes and the facts are re-read
        serveModified(r -> r.put("timestamp", 1L));
        monitors.markDue(StudyinfoAdapter.NAME, T1);
        var run = refresh.runDueForAdapter(StudyinfoAdapter.NAME, T1);

        assertThat(run.changed()).as("the page hash did change").isEqualTo(1);
        assertThat(run.pendingReview()).isZero();
        assertThat(run.autoApplied()).isZero();
        assertThat(snapshot()).as("but every fact compared equal, so no row was rewritten").isEqualTo(before);
    }

    @Test
    void aControlledAcademicFieldChangeIsStagedThenPersistedWithItsHistoryOnApproval() {
        importer.run(false, null, null);

        serveModified(r -> StudyinfoParserTest.first(r, "koulutuskoodienAlatJaAsteet").put("koulutusalaKoodiUrit",
                List.of("kansallinenkoulutusluokitus2016koulutusalataso1_05", "kansallinenkoulutusluokitus2016koulutusalataso2_054")));
        monitors.markDue(StudyinfoAdapter.NAME, T1);
        var run = refresh.runDueForAdapter(StudyinfoAdapter.NAME, T1);

        // parsed and validated, but a classification change is never published silently
        assertThat(run.pendingReview()).isEqualTo(1);
        assertThat(field("f.slug")).as("published value unchanged until a human approves").isEqualTo("computer-and-data-science");
        var staged = jdbc.queryForMap("select id, fact_type, old_value, new_value, risk, status from staged_change where status = 'PENDING_REVIEW'");
        assertThat(staged).containsEntry("fact_type", "ACADEMIC_FIELD").containsEntry("risk", "HIGH");
        assertThat((String) staged.get("old_value")).isEqualTo("computer-and-data-science");
        assertThat((String) staged.get("new_value")).contains("mathematics-and-statistics");

        review.approve(((Number) staged.get("id")).longValue(), "test-reviewer");

        assertThat(field("f.slug")).isEqualTo("mathematics-and-statistics");
        assertThat(field("p.field_of_study")).isEqualTo("Mathematics and Statistics");
        // the replaced value is preserved, labelled as superseded, with the source that had stated it
        var history = jdbc.queryForMap("select field_name, value, note, recorded_source_id is not null as sourced from program_fact_history where program_id = ?", programId());
        assertThat(history).containsEntry("field_name", "academic_field").containsEntry("value", "computer-and-data-science").containsEntry("sourced", true);
        assertThat((String) history.get("note")).contains("does not state when the old value stopped being valid");
        assertThat(jdbc.queryForObject("select status from staged_change where id = ?", String.class, staged.get("id"))).isEqualTo("APPROVED");

        // re-reading the same modified record changes nothing further
        monitors.markDue(StudyinfoAdapter.NAME, T1.plusSeconds(60));
        var settled = snapshot();
        refresh.runDueForAdapter(StudyinfoAdapter.NAME, T1.plusSeconds(60));
        assertThat(snapshot()).isEqualTo(settled);
    }

    @Test
    void renamingAProgramKeepsItsIdentifierItsLinksAndItsProviderIdAndCreatesNoDuplicate() throws Exception {
        importer.run(false, null, null);
        String identifier = "zz-studyinfo-u--computer-science-and-engineering-master-of-science";
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/programs/" + identifier))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        var pageBefore = field("p.official_program_url");
        var idsBefore = jdbc.queryForList("select scheme || '=' || value from program_external_id order by 1", String.class);

        serveModified(r -> StudyinfoParserTest.first(r, "toteutukset").put("nimi", Map.of("en", "Computer Science and Engineering (MSc in Technology)")));
        monitors.markDue(StudyinfoAdapter.NAME, T1);
        refresh.runDueForAdapter(StudyinfoAdapter.NAME, T1);
        long id = jdbc.queryForObject("select id from staged_change where status = 'PENDING_REVIEW' and fact_type = 'PROGRAM_NAME'", Long.class);
        review.approve(id, "test-reviewer");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/programs/" + identifier))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.name").value("Computer Science and Engineering (MSc in Technology)"));
        assertThat(field("p.slug")).isEqualTo("computer-science-and-engineering-master-of-science");
        assertThat(field("p.official_program_url")).isEqualTo(pageBefore);
        assertThat(jdbc.queryForList("select scheme || '=' || value from program_external_id order by 1", String.class)).isEqualTo(idsBefore);
        assertThat(jdbc.queryForObject("select value from program_fact_history where field_name = 'name'", String.class))
                .isEqualTo("Computer Science and Engineering, Master of Science (Technology) (2 years)");
        // a later import recognises the program by its provider id and does not create a second one under the new name
        assertThat(importer.run(false, null, null).created()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from degree_program p join university u on u.id = p.university_id where u.slug = 'zz-studyinfo-u'", Long.class)).isEqualTo(1);
    }

    @Test
    void aRejectedChangeIsNeverProposedAgainForTheSameValue() {
        importer.run(false, null, null);
        serveModified(r -> StudyinfoParserTest.first(r, "koulutuskoodienAlatJaAsteet").put("koulutusalaKoodiUrit",
                List.of("kansallinenkoulutusluokitus2016koulutusalataso2_054")));
        monitors.markDue(StudyinfoAdapter.NAME, T1);
        refresh.runDueForAdapter(StudyinfoAdapter.NAME, T1);
        long id = jdbc.queryForObject("select id from staged_change where status = 'PENDING_REVIEW'", Long.class);
        review.reject(id, "test-reviewer", "classification of this program is intentionally different");

        // a later provider re-index changes the page hash again, with the same disputed field
        serveModified(r -> {
            StudyinfoParserTest.first(r, "koulutuskoodienAlatJaAsteet").put("koulutusalaKoodiUrit",
                    List.of("kansallinenkoulutusluokitus2016koulutusalataso2_054"));
            r.put("timestamp", 2L);
        });
        monitors.markDue(StudyinfoAdapter.NAME, T1.plusSeconds(60));
        refresh.runDueForAdapter(StudyinfoAdapter.NAME, T1.plusSeconds(60));

        assertThat(jdbc.queryForObject("select count(*) from staged_change where status = 'PENDING_REVIEW'", Long.class)).isZero();
        assertThat(field("f.slug")).isEqualTo("computer-and-data-science");
    }

    @Test
    void aChangedDurationIsStagedAndNeverOverwritesThePublishedValue() {
        importer.run(false, null, null);
        serveModified(r -> StudyinfoParserTest.opetus(StudyinfoParserTest.first(r, "toteutukset")).put("suunniteltuKestoVuodet", 3));
        monitors.markDue(StudyinfoAdapter.NAME, T1);
        var run = refresh.runDueForAdapter(StudyinfoAdapter.NAME, T1);

        assertThat(run.pendingReview()).isEqualTo(1);
        assertThat(run.autoApplied()).isZero();
        assertThat(field("p.duration_months")).isEqualTo("24");
        assertThat(jdbc.queryForObject("select new_value from staged_change where fact_type = 'DURATION_MONTHS' and status = 'PENDING_REVIEW'", String.class)).contains("36");
    }

    @Test
    void aChangedClosingDateIsStagedAndTheStoredDeadlineStaysUntilReviewed() {
        importer.run(false, null, null);
        serveModified(r -> ((Map<String, Object>) ((List<Object>) StudyinfoParserTest.first(r, "haut").get("hakuajat")).get(0)).put("paattyy", "2027-01-28T15:00"));
        monitors.markDue(StudyinfoAdapter.NAME, T1);
        var run = refresh.runDueForAdapter(StudyinfoAdapter.NAME, T1);

        assertThat(run.pendingReview()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select application_deadline::text from program_intake where program_id = ?", String.class, programId())).isEqualTo("2027-01-21");
        var staged = jdbc.queryForMap("select id, fact_type, old_value from staged_change where status = 'PENDING_REVIEW'");
        assertThat(staged).containsEntry("fact_type", "DEADLINE").containsEntry("old_value", "2027-01-21");
        long id = ((Number) staged.get("id")).longValue();
        // dated facts are never overwritten, even by an approval: the existing rule applies unchanged to this provider
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> review.approve(id, "test-reviewer"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Dated facts are never overwritten");
    }

    @Test
    void anUnpublishedRecordPublishesNothingAndAsksForReviewInsteadOfDeletingFacts() {
        importer.run(false, null, null);
        var before = snapshot();
        serveModified(r -> r.put("tila", "arkistoitu"));
        monitors.markDue(StudyinfoAdapter.NAME, T1);
        var run = refresh.runDueForAdapter(StudyinfoAdapter.NAME, T1);

        assertThat(run.pendingReview()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select fact_type from staged_change where status = 'PENDING_REVIEW'", String.class)).isEqualTo("SOURCE_CONTENT");
        var after = snapshot();
        assertThat(after.get("provenance")).isEqualTo(before.get("provenance"));
        assertThat(field("p.name")).startsWith("Computer Science and Engineering");
    }

    @Test
    void aProviderOutageKeepsEveryPublishedFactAndOnlyRecordsTheFailure() {
        importer.run(false, null, null);
        var before = snapshot();
        transport.reset(record, new IOException("connection reset"));
        monitors.markDue(StudyinfoAdapter.NAME, T1);
        var run = refresh.runDueForAdapter(StudyinfoAdapter.NAME, T1);

        assertThat(run.failed()).isEqualTo(1);
        assertThat(snapshot()).isEqualTo(before);
        var m = monitors.findBySourceId(jdbc.queryForObject("select id from source where url = ?", Long.class, record)).orElseThrow();
        assertThat(m.failureCount()).isEqualTo(1);
        assertThat(m.lastResult()).isEqualTo("FAILED");
        assertThat(m.lastSuccessAt()).as("the last successful refresh is not advanced by a failure").isNotEqualTo(T1);
    }

    @Test
    void aRecordWhoseInstitutionDiffersFromTheConfiguredOneIsNotImported() {
        serveModified(r -> ((Map<String, Object>) ((List<Object>) r.get("tarjoajat")).get(0)).put("oid", "1.2.246.562.10.99999999999"));
        var summary = importer.run(false, null, null);
        assertThat(summary.created()).isZero();
        assertThat(summary.skipped()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from degree_program p join program_external_id e on e.program_id = p.id", Long.class)).isZero();
    }

    private void listUnderConfiguredInstitution() {
        transport.reset(TARJONTA, FakeTransport.text("application/json", """
                {"total":1,"hits":[{"koulutustyyppi":"yo","koulutusOid":"%s","oppilaitosOid":"1.2.246.562.10.38417217288",
                 "opetuskielet":["oppilaitoksenopetuskieli_4"],"nimi":{"en":"Computer Science and Engineering"}}]}
                """.formatted(KOULUTUS)));
    }

    @Test
    void aRecordNamingAUnitInsideTheUniversityIsImportedWhenTheProvidersOwnSearchListsItUnderThatUniversity() {
        listUnderConfiguredInstitution();
        serveModified(r -> ((Map<String, Object>) ((List<Object>) r.get("tarjoajat")).get(0)).put("oid", "1.2.246.562.10.56753942459"));
        assertThat(importer.run(false, null, null).created()).isEqualTo(1);
    }

    @Test
    void aJointProgramWithTwoProvidersIsNeverImportedEvenWhenListed() {
        listUnderConfiguredInstitution();
        serveModified(r -> ((List<Object>) r.get("tarjoajat")).add(Map.of("oid", "1.2.246.562.10.11111111111", "nimi", Map.of("en", "Partner University"))));
        var summary = importer.run(false, null, null);
        assertThat(summary.created()).isZero();
        assertThat(summary.notes()).anyMatch(n -> n.contains("expected exactly one providing institution"));
    }

    @Test
    void aRecordNamingAnotherInstitutionTheSearchDidNotListUnderThisOneIsRefused() {
        transport.reset(TARJONTA, FakeTransport.text("application/json", """
                {"total":1,"hits":[{"koulutustyyppi":"yo","koulutusOid":"%s","oppilaitosOid":"1.2.246.562.10.22222222222",
                 "opetuskielet":["oppilaitoksenopetuskieli_4"],"nimi":{"en":"X"}}]}
                """.formatted(KOULUTUS)));
        serveModified(r -> ((Map<String, Object>) ((List<Object>) r.get("tarjoajat")).get(0)).put("oid", "1.2.246.562.10.33333333333"));
        assertThat(importer.run(false, null, null).created()).isZero();
    }

    @Test
    void aDryRunReadsButWritesNothing() {
        var summary = importer.run(true, null, null);
        assertThat(summary.created()).isZero();
        assertThat(summary.notes()).anyMatch(n -> n.contains("WOULD IMPORT " + KOULUTUS));
        assertThat(jdbc.queryForObject("select count(*) from source where url like 'https://opintopolku.fi/%'", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from program_external_id", Long.class)).isZero();
    }
}

package com.pathwayai.backend.provider.universitypages;

import static org.assertj.core.api.Assertions.*;
import com.pathwayai.backend.TestcontainersConfiguration;
import com.pathwayai.backend.refresh.support.FakeTransport;
import com.pathwayai.backend.refresh.support.RefreshTestConfig;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {"app.refresh.min-host-interval-ms=0", "app.refresh.extra-approved-hosts=www.oulu.fi"})
@Import({TestcontainersConfiguration.class, RefreshTestConfig.class})
@Transactional
class OfficialLinkBackfillIT {
    @Autowired OfficialLinkBackfill backfill;
    @Autowired JdbcTemplate jdbc;
    @Autowired FakeTransport transport;
    long program;
    static final String OLD = "https://opintopolku.fi/konfo/en/koulutus/synthetic-oid";

    @BeforeEach void seed() {
        jdbc.update("insert into country (iso2_code,name) values ('ZZ','Synthetic country') on conflict do nothing");
        long university = jdbc.queryForObject("""
                insert into university (country_id,slug,name) select id,'university-of-oulu','Synthetic University'
                from country where iso2_code='ZZ' returning id""", Long.class);
        program = jdbc.queryForObject("""
                insert into degree_program (university_id,slug,name,degree_level,official_program_url)
                values (?,'synthetic-program','Synthetic Programme','MASTER',?) returning id""", Long.class, university, OLD);
        jdbc.update("insert into program_external_id (program_id,scheme,value) values (?,'STUDYINFO_KOULUTUS_OID','synthetic-oid')", program);
        long source = jdbc.queryForObject("""
                insert into source (url,title,source_type,retrieved_on)
                values (?,'Synthetic provider','GOVERNMENT',current_date) returning id""", Long.class, OLD);
        jdbc.update("insert into program_source (program_id,source_id) values (?,?)", program, source);
        transport.reset(OfficialProgramPagesTest.URL, FakeTransport.text("text/html", OfficialProgramPagesTest.HTML));
    }

    String url() { return jdbc.queryForObject("select official_program_url from degree_program where id=?", String.class, program); }
    long count(String table) { return jdbc.queryForObject("select count(*) from " + table, Long.class); }
    List<OfficialLinkBackfill.Result> run(boolean commit) { return backfill.run(commit, List.of(OfficialProgramPagesTest.MATCH)); }

    @Test void previewWritesNothingThenCommitIsRepeatableAndRetainsIdentifiersAndHistory() {
        var before = jdbc.queryForList("select * from program_external_id");
        assertThat(run(false).getFirst().status()).isEqualTo("VERIFIED");
        assertThat(url()).isEqualTo(OLD);
        assertThat(count("source_check")).isZero();
        run(true);
        assertThat(url()).isEqualTo(OfficialProgramPagesTest.URL);
        assertThat(jdbc.queryForObject("select value from program_fact_history where program_id=?", String.class, program)).isEqualTo(OLD);
        assertThat(jdbc.queryForObject("select mechanism from program_field_provenance where program_id=?", String.class, program))
                .isEqualTo("REVIEWED_PAGE");
        run(true);
        assertThat(count("program_fact_history")).isEqualTo(1);
        assertThat(jdbc.queryForList("select * from program_external_id")).isEqualTo(before);
        assertThat(count("degree_program")).isEqualTo(1);
    }

    @Test void incorrectAssociationIsUnknownAndNeverFetched() {
        jdbc.update("update degree_program set degree_level='BACHELOR' where id=?", program);
        transport.clearRequests();
        assertThat(run(false).getFirst().status()).isEqualTo("UNKNOWN");
        assertThat(transport.countFor(OfficialProgramPagesTest.URL)).isZero();
        assertThat(url()).isEqualTo(OLD);
    }

    @Test void blockedOrChangedPageCannotEraseACorrectExistingUniversityLink() {
        run(true);
        transport.reset(OfficialProgramPagesTest.URL, FakeTransport.status(403));
        assertThat(run(true).getFirst().status()).isEqualTo("PENDING_REVIEW");
        assertThat(url()).isEqualTo(OfficialProgramPagesTest.URL);
        transport.reset(OfficialProgramPagesTest.URL, FakeTransport.text("text/html", "<h1>Unrelated programme</h1>"));
        assertThat(run(true).getFirst().status()).isEqualTo("PENDING_REVIEW");
        assertThat(url()).isEqualTo(OfficialProgramPagesTest.URL);
        assertThat(count("program_fact_history")).isEqualTo(1);
    }

    @Test void missingMappingClearsOnlyLegacyPortalDestinationAndKeepsSource() {
        assertThat(backfill.run(true, List.of()).getFirst().status()).isEqualTo("UNKNOWN");
        assertThat(url()).isNull();
        assertThat(count("program_source")).isEqualTo(1);
        assertThat(count("program_external_id")).isEqualTo(1);
        backfill.run(true, List.of());
        assertThat(count("program_fact_history")).isEqualTo(1);
    }
}

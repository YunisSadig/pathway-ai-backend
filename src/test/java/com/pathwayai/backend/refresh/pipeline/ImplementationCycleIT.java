package com.pathwayai.backend.refresh.pipeline;

import static org.assertj.core.api.Assertions.*;
import com.pathwayai.backend.TestcontainersConfiguration;
import com.pathwayai.backend.provider.studyinfo.StudyinfoAdapter;
import com.pathwayai.backend.refresh.extract.ExtractedFact;
import com.pathwayai.backend.refresh.registry.SourceMonitor;
import com.pathwayai.backend.refresh.registry.SourceMonitorRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Adopting a verified successor implementation: audited, idempotent, identity-preserving, never cross-program. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ImplementationCycleIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired ChangePipeline pipeline;
    @Autowired SourceMonitorRepository monitors;
    static final Instant NOW = Instant.parse("2027-11-20T05:00:00Z");
    static final String EDU = "1.2.246.562.13.00000000000000099999";
    static final String OLD = "1.2.246.562.17.00000000000000099998";
    static final String NEW = "1.2.246.562.17.00000000000099990001";
    long program;
    SourceMonitor monitor;

    @BeforeEach void seed() {
        jdbc.update("insert into country(iso2_code,name) values('ZZ','Synthetic country') on conflict do nothing");
        long university = jdbc.queryForObject("insert into university(country_id,slug,name) select id,'zz-cycle-u','Synthetic University' from country where iso2_code='ZZ' returning id", Long.class);
        program = jdbc.queryForObject("insert into degree_program(university_id,slug,name,degree_level) values(?,'p','Synthetic Programme','MASTER') returning id", Long.class, university);
        jdbc.update("insert into program_external_id(program_id,scheme,value) values(?,'STUDYINFO_KOULUTUS_OID',?),(?,'STUDYINFO_TOTEUTUS_OID',?)", program, EDU, program, OLD);
        jdbc.update("insert into catalog_release(id,country_code,enabled,approved_by,approved_at) values('zz-cycle','ZZ',true,'synthetic-reviewer',now())");
        jdbc.update("insert into catalog_membership(release_id,program_id,entry_level,qualification_pathway,education_oid,implementation_oid) values('zz-cycle',?,'MASTER','SINGLE_DEGREE',?,?)", program, EDU, OLD);
        long source = jdbc.queryForObject("insert into source(url,title,source_type,retrieved_on) values('https://example.test/synthetic-record','Synthetic record','GOVERNMENT',current_date) returning id", Long.class);
        monitors.register(source, new SourceMonitor.Spec("https://example.test/synthetic-record", university, program, "PROGRAM", "identity",
                "ADAPTER", StudyinfoAdapter.NAME, StudyinfoAdapter.VERSION, 7), "VERIFIED", NOW);
        monitor = monitors.findBySourceId(source).orElseThrow();
    }

    ExtractedFact move(String to) {
        return ExtractedFact.of(ExtractedFact.FactType.IMPLEMENTATION, "", Map.of("educationOid", EDU, "implementationOid", to, "previous", OLD));
    }
    String current() { return jdbc.queryForObject("select implementation_oid from catalog_membership where program_id=?", String.class, program); }

    @Test void aVerifiedSuccessorIsAdoptedAutomaticallyWithHistoryAndBothIdentifiersKept() {
        var outcome = pipeline.process(monitor, List.of(move(NEW)), "synthetic-1", NOW);
        assertThat(outcome.autoApplied()).isEqualTo(1);
        assertThat(current()).isEqualTo(NEW);
        assertThat(jdbc.queryForList("select value from program_external_id where program_id=? and scheme='STUDYINFO_TOTEUTUS_OID' order by value", String.class, program))
                .containsExactly(OLD, NEW);
        assertThat(jdbc.queryForObject("select value from program_fact_history where program_id=? and field_name='implementation_oid'", String.class, program)).isEqualTo(OLD);
        assertThat(jdbc.queryForObject("select status from staged_change where program_id=? and fact_type='IMPLEMENTATION'", String.class, program)).isEqualTo("AUTO_APPLIED");
        // the next read of the same record changes nothing
        assertThat(pipeline.process(monitor, List.of(move(NEW)), "synthetic-1", NOW.plusSeconds(60)).unchanged()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from degree_program where id=?", Long.class, program)).isEqualTo(1);
    }

    @Test void anImplementationOwnedByAnotherProgramIsNeverTaken() {
        long other = jdbc.queryForObject("insert into degree_program(university_id,slug,name,degree_level) select university_id,'q','Synthetic Other','MASTER' from degree_program where id=? returning id", Long.class, program);
        jdbc.update("insert into program_external_id(program_id,scheme,value) values(?,'STUDYINFO_TOTEUTUS_OID',?)", other, NEW);
        assertThatThrownBy(() -> pipeline.process(monitor, List.of(move(NEW)), "synthetic-1", NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThat(current()).isEqualTo(OLD);
    }
}

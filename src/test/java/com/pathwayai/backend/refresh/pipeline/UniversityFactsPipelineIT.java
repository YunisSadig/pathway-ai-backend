package com.pathwayai.backend.refresh.pipeline;

import static org.assertj.core.api.Assertions.*;
import com.pathwayai.backend.TestcontainersConfiguration;
import com.pathwayai.backend.refresh.extract.ExtractedFact;
import com.pathwayai.backend.refresh.registry.SourceMonitor;
import com.pathwayai.backend.refresh.registry.SourceMonitorRepository;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class UniversityFactsPipelineIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired ChangePipeline pipeline;
    @Autowired SourceMonitorRepository monitors;
    @Autowired StagedChangeService review;
    long program,source;
    SourceMonitor monitor;
    static final Instant NOW=Instant.parse("2026-11-16T10:00:00Z");
    @BeforeEach void seed() {
        jdbc.update("insert into country(iso2_code,name) values('ZF','Synthetic Factland') on conflict do nothing");
        long university=jdbc.queryForObject("insert into university(country_id,slug,name) select id,'zz-fact-u','Synthetic Fact University' from country where iso2_code='ZF' returning id",Long.class);
        program=jdbc.queryForObject("insert into degree_program(university_id,slug,name,degree_level) values(?,'p','Synthetic Fact Program','MASTER') returning id",Long.class,university);
        jdbc.update("insert into program_external_id(program_id,scheme,value) values(?,'STUDYINFO_KOULUTUS_OID','synthetic-fact-oid')",program);
        source=jdbc.queryForObject("insert into source(url,title,source_type,retrieved_on) values('https://example.test/synthetic-source','Synthetic source','UNIVERSITY_WEBSITE',current_date) returning id",Long.class);
        monitors.register(source,new SourceMonitor.Spec("https://example.test/synthetic-source",university,program,"PROGRAM","tuition","ADAPTER","university-program","1",7),"VERIFIED",NOW);
        monitor=monitors.findBySourceId(source).orElseThrow();
    }
    ExtractedFact fee(String value) {return ExtractedFact.of(ExtractedFact.FactType.UNIVERSITY_FACT,"tuition_scope|",
            Map.of("field","tuition_scope","value",value,"educationOid","synthetic-fact-oid","scope",""));}

    @Test void unchangedFeesAreIdempotentAndChangedApplicantScopedFeesWaitForReviewWithHistory() {
        String original="10000 EUR per year for non-EU/EEA students; exemptions apply";
        assertThat(pipeline.process(monitor,java.util.List.of(fee(original)),"synthetic-1",NOW,true).autoApplied()).isEqualTo(1);
        assertThat(pipeline.process(monitor,java.util.List.of(fee(original)),"synthetic-1",NOW,false).unchanged()).isEqualTo(1);
        assertThat(pipeline.process(monitor,java.util.List.of(fee(original.replace("10000","12000"))),"synthetic-1",NOW,false).pendingReview()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select value from program_university_fact where program_id=?",String.class,program)).isEqualTo(original);
        long pending=jdbc.queryForObject("select id from staged_change where program_id=? and status='PENDING_REVIEW'",Long.class,program);
        review.approve(pending,"synthetic-reviewer");
        assertThat(jdbc.queryForObject("select value from program_fact_history where program_id=?",String.class,program)).isEqualTo(original);
        assertThat(jdbc.queryForObject("select value from program_university_fact where program_id=?",String.class,program)).contains("non-EU/EEA").contains("12000");
    }

    @Test void wrongOfferingAssociationCannotPublishAFact() {
        var wrong=ExtractedFact.of(ExtractedFact.FactType.UNIVERSITY_FACT,"credits|",Map.of("field","credits","value","120 ECTS","educationOid","other-oid"));
        assertThatThrownBy(()->pipeline.process(monitor,java.util.List.of(wrong),"synthetic-1",NOW,true)).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("select count(*) from program_university_fact where program_id=?",Long.class,program)).isZero();
    }
}

package com.pathwayai.backend.provider.universitypages;

import static org.assertj.core.api.Assertions.*;
import com.pathwayai.backend.TestcontainersConfiguration;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * The itemised, read-only status section runs against the real schema and shows a source-stated-year-less fee as
 * "not stated" (synthetic fixture only).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class CuratedStatusDetailIT {
    @Autowired JdbcClient jdbcClient;
    @Autowired JdbcTemplate jdbc;

    @Test void detailSqlRunsOnTheMigratedSchemaAndReportsNothingForAnEmptyCatalogue() {
        var cli=new CuratedOperationsCli(null,null,jdbcClient,Clock.systemUTC(),"status");
        List<String> lines=cli.detailLines();
        assertThat(lines).contains("== Curated data detail","tuition rows on curated programs: 0","tuition rows with an academic year: 0");
    }

    @Test void anUndatedFeeIsShownWithItsAudienceAndSourceAndNoInventedYear() {
        jdbc.update("insert into country(iso2_code,name) values('ZZ','Synthetic country') on conflict do nothing");
        long university=jdbc.queryForObject("insert into university(country_id,slug,name,verification_status,verified_on) select id,'synthetic-detail-university','Synthetic University','VERIFIED',date '2026-11-11' from country where iso2_code='ZZ' returning id",Long.class);
        long source=jdbc.queryForObject("insert into source(url,title,source_type,retrieved_on) values('https://example.invalid/fee','Synthetic source','OTHER',date '2026-11-11') returning id",Long.class);
        long program=jdbc.queryForObject("insert into degree_program(university_id,slug,name,degree_level) values(?,'synthetic-detail-program','Synthetic Program','MASTER') returning id",Long.class,university);
        jdbc.update("insert into tuition_fee(program_id,source_id,student_category,amount,currency,billing_period) values(?,?,'INTERNATIONAL',12000,'EUR','PER_YEAR')",program,source);
        jdbc.update("insert into catalog_release(id,country_code,enabled,approved_by,approved_at) values('synthetic-detail-release','ZZ',true,'test',now())");
        jdbc.update("insert into catalog_membership(release_id,program_id,entry_level,qualification_pathway,education_oid,implementation_oid) values('synthetic-detail-release',?,'MASTER','SINGLE_DEGREE','1.2.3','1.2.4')",program);
        List<String> lines=new CuratedOperationsCli(null,null,jdbcClient,Clock.systemUTC(),"status").detailLines();
        assertThat(lines).contains("tuition rows on curated programs: 1","tuition rows with an academic year: 0");
        assertThat(lines).contains("tuition synthetic-detail-program | INTERNATIONAL | 12000 EUR | billing PER_YEAR | academic year: not stated | source: https://example.invalid/fee");
    }
}

package com.pathwayai.backend.provider.universitypages;

import static org.assertj.core.api.Assertions.*;
import com.pathwayai.backend.TestcontainersConfiguration;
import com.pathwayai.backend.provider.studyinfo.FieldTaxonomy;
import com.pathwayai.backend.provider.studyinfo.StudyinfoImporter;
import com.pathwayai.backend.provider.studyinfo.StudyinfoParser;
import com.pathwayai.backend.refresh.fetch.PageFetcher;
import com.pathwayai.backend.refresh.support.RefreshTestConfig;
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

/**
 * Regression for the restored-production rehearsal (run 44166523352): a curated import may only ADD a provenance link
 * from a pre-existing university to the Studyinfo record of a program it newly creates there. The university row, its
 * identifiers, aliases and every existing source link stay exactly as they were; reusing a program adds nothing.
 */
@SpringBootTest(properties="app.refresh.min-host-interval-ms=0")
@Import({TestcontainersConfiguration.class,RefreshTestConfig.class})
@Transactional
class CuratedImportProvenanceIT {
    @Autowired StudyinfoImporter importer;
    @Autowired CuratedSources sources;
    @Autowired JdbcTemplate jdbc;
    static final Instant NOW=Instant.parse("2026-11-18T06:00:00Z");
    long university;

    @BeforeEach void seed() {
        jdbc.update("insert into country(iso2_code,name) values('ZZ','Synthetic country') on conflict do nothing");
        university=jdbc.queryForObject("insert into university(country_id,slug,name,verification_status,verified_on) select id,'aalto-university','Synthetic University','VERIFIED',date '2026-11-11' from country where iso2_code='ZZ' returning id",Long.class);
        jdbc.update("insert into university_external_id(university_id,scheme,value) values(?,'SCHAC_HOME_ORG','synthetic.example')",university);
        jdbc.update("insert into university_alias(university_id,alias) values(?,'SYNTHETIC ALIAS')",university);
        long existingSource=jdbc.queryForObject("insert into source(url,title,source_type,retrieved_on) values('https://example.test/registry','Synthetic registry','OTHER',date '2026-11-11') returning id",Long.class);
        jdbc.update("insert into university_source(university_id,source_id) values(?,?)",university,existingSource);
    }

    List<Map<String,Object>> rows(String sql){return jdbc.queryForList(sql,university);}
    Map<String,List<Map<String,Object>>> universityState(){
        return Map.of("university",rows("select * from university where id=?"),
                "ids",rows("select * from university_external_id where university_id=? order by scheme,value"),
                "aliases",rows("select * from university_alias where university_id=? order by alias"));
    }
    List<Map<String,Object>> links(){return rows("select university_id,source_id from university_source where university_id=? order by source_id");}

    long importCurated(String key) throws Exception {
        var c=sources.candidates().stream().filter(x->x.key().equals(key)).findFirst().orElseThrow();
        String json=CuratedSourceEvidenceTest.fixture(key+".json");
        var program=StudyinfoParser.parse(json,FieldTaxonomy.of(Map.of()),c.implementationOid(),c.combined(),c.providerOid(),c.titleCodes()).program();
        var response=new PageFetcher.Result(PageFetcher.Kind.OK,200,importer.recordUrl(c.educationOid()),"application/json",json,null,null,null,null,1);
        return importer.importReviewed(c,program,response,NOW);
    }

    @Test void creatingACuratedProgramOnlyAddsTheLinkToItsOwnStudyinfoRecord() throws Exception {
        var before=universityState();
        var linksBefore=links();
        long program=importCurated("aalto-data-science");
        assertThat(universityState()).isEqualTo(before);
        var linksAfter=links();
        assertThat(linksAfter).containsAll(linksBefore).hasSize(linksBefore.size()+1);
        var added=new java.util.ArrayList<>(linksAfter);added.removeAll(linksBefore);
        long addedSource=((Number)added.getFirst().get("source_id")).longValue();
        assertThat(jdbc.queryForObject("select count(*) from program_source where program_id=? and source_id=?",Long.class,program,addedSource)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select url from source where id=?",String.class,addedSource)).contains("1.2.246.562.13.00000000000000002815");
    }

    @Test void reusingAnExistingProgramAddsNoLinkAndChangesNothing() throws Exception {
        long first=importCurated("aalto-computer-science");
        var before=universityState();
        var linksBefore=links();
        long again=importCurated("aalto-computer-science");
        assertThat(again).isEqualTo(first);
        assertThat(universityState()).isEqualTo(before);
        assertThat(links()).isEqualTo(linksBefore);
        assertThat(jdbc.queryForObject("select count(*) from degree_program where university_id=?",Long.class,university)).isEqualTo(1);
    }
}

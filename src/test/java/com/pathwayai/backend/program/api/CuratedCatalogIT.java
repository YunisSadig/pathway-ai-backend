package com.pathwayai.backend.program.api;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.pathwayai.backend.TestcontainersConfiguration;
import com.pathwayai.backend.program.service.ProgramSearchCriteria;
import com.pathwayai.backend.program.service.ProgramService;
import com.pathwayai.backend.university.service.UniversityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties="app.curated.enabled=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class CuratedCatalogIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired ProgramService programs;
    @Autowired UniversityService universities;
    @Autowired MockMvc mvc;
    long university;
    @BeforeEach void seed() {
        jdbc.update("insert into country(iso2_code,name) values('FI','Synthetic Curated Finland') on conflict do nothing");
        university=jdbc.queryForObject("""
                insert into university(country_id,slug,name,verification_status,verified_on)
                select id,'zz-curated-u','Synthetic Curated University','VERIFIED',date '2026-11-16' from country where iso2_code='FI' returning id""",Long.class);
        jdbc.update("insert into catalog_release(id,country_code) values('synthetic-curated-release','FI')");
        for(int i=0;i<21;i++) {
            String level=i<10?"BACHELOR":"MASTER";
            long id=jdbc.queryForObject("insert into degree_program(university_id,slug,name,degree_level) values(?,?,?,?) returning id",
                    Long.class,university,"p-"+i,"Synthetic Curated Program "+i,level);
            if(i<20)jdbc.update("insert into catalog_membership(release_id,program_id,entry_level,qualification_pathway,education_oid,implementation_oid,study_option) values('synthetic-curated-release',?,?,?,?,?,?)",
                    id,level,i<10?"BACHELOR_AND_MASTER":"SINGLE_DEGREE","synthetic-education-"+i,"synthetic-implementation-"+i,i==0?"synthetic study track":null);
        }
        jdbc.update("update catalog_release set enabled=true,approved_by='synthetic-reviewer',approved_at=now() where id='synthetic-curated-release'");
    }

    @Test void publicFiltersCountsAndPaginationShowExactlyTenPlusTenWithoutDeletingOtherPrograms() throws Exception {
        mvc.perform(get("/api/v1/public/programs?country=FI&size=5").param("q","Synthetic Curated"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(20)).andExpect(jsonPath("$.totalPages").value(4));
        mvc.perform(get("/api/v1/public/programs?country=FI&level=bachelor"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(10));
        mvc.perform(get("/api/v1/public/programs?country=FI&level=master"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(10));
        assertThat(jdbc.queryForObject("select count(*) from degree_program where university_id=?",Long.class,university)).isEqualTo(21);
    }

    @Test void oldProgramLinksRemainAccessibleAndExplicitlyOutsideCuration() throws Exception {
        assertThat(programs.getByIdentifier("zz-curated-u--p-20").catalogMembership()).isEqualTo("OUTSIDE_CURATED_CATALOG");
        mvc.perform(get("/api/v1/public/programs/zz-curated-u--p-20"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.catalogMembership").value("OUTSIDE_CURATED_CATALOG"))
                .andExpect(jsonPath("$.universityInformation").doesNotExist());
    }

    @Test void universityListingsUseTheSameMembershipAndImportedExtraRowsCannotReappear() {
        assertThat(universities.getBySlug("zz-curated-u").programs()).hasSize(20);
        jdbc.update("insert into degree_program(university_id,slug,name,degree_level) values(?,'future-import','Synthetic Curated future import','MASTER')",university);
        var query=new ProgramSearchCriteria(null,null,null,"FI","zz-curated-u",null,null,null,null);
        assertThat(programs.search(query,PageRequest.of(0,100)).totalElements()).isEqualTo(20);
        assertThat(jdbc.queryForObject("select count(*) from degree_program where university_id=?",Long.class,university)).isEqualTo(22);
    }

    @Test void curatedDetailNamesItsPathwayAndTheSourcedStudyOptionOfTheParentDegree() {
        var detail=programs.getByIdentifier("zz-curated-u--p-0");
        assertThat(detail.catalogMembership()).isEqualTo("CURATED");
        assertThat(detail.universityInformation().qualificationPathway()).isEqualTo("BACHELOR_AND_MASTER");
        assertThat(detail.universityInformation().studyOption()).isEqualTo("synthetic study track");
        assertThat(programs.getByIdentifier("zz-curated-u--p-11").universityInformation().studyOption()).isNull();
    }

    @Test void aBrokenOfficialLinkIsReportedAsBrokenNotAsVerified() {
        long id=jdbc.queryForObject("select id from degree_program where slug='p-1'",Long.class);
        jdbc.update("insert into program_link_status(program_id,link_type,url,status,consecutive_failures,last_checked_at) values(?,'PROGRAM_PAGE','https://example.test/synthetic','BROKEN',2,now())",id);
        var links=programs.getByIdentifier("zz-curated-u--p-1").universityInformation().linkChecks();
        assertThat(links).singleElement().satisfies(l->{
            assertThat(l.linkType()).isEqualTo("PROGRAM_PAGE");
            assertThat(l.status()).isEqualTo("BROKEN");
            assertThat(l.lastVerifiedAt()).isNull();
        });
    }
}

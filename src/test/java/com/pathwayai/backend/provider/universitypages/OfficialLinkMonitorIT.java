package com.pathwayai.backend.provider.universitypages;

import static org.assertj.core.api.Assertions.*;
import com.pathwayai.backend.TestcontainersConfiguration;
import com.pathwayai.backend.refresh.support.FakeTransport;
import com.pathwayai.backend.refresh.support.RefreshTestConfig;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Recorded Oulu program page (2026-11-17) served by a fake transport; no live site is contacted. */
@SpringBootTest(properties={"app.refresh.min-host-interval-ms=0","app.refresh.extra-approved-hosts=www.oulu.fi"})
@Import({TestcontainersConfiguration.class,RefreshTestConfig.class})
@Transactional
class OfficialLinkMonitorIT {
    @Autowired OfficialLinkMonitor monitor;
    @Autowired CuratedSources sources;
    @Autowired FakeTransport transport;
    @Autowired JdbcTemplate jdbc;
    static final Instant NOW=Instant.parse("2026-11-18T06:00:00Z");
    static final String SITEMAP="https://www.oulu.fi/sitemap.xml";
    CuratedSources.Candidate oulu;
    String page;
    long program;

    @BeforeEach void seed() throws Exception {
        oulu=sources.candidates().stream().filter(c->c.key().equals("oulu-software")).findFirst().orElseThrow();
        page=CuratedSourceEvidenceTest.fixture("oulu-software.html");
        jdbc.update("insert into country(iso2_code,name) values('ZZ','Synthetic country') on conflict do nothing");
        long university=jdbc.queryForObject("insert into university(country_id,slug,name) select id,'university-of-oulu','Synthetic University' from country where iso2_code='ZZ' returning id",Long.class);
        program=jdbc.queryForObject("insert into degree_program(university_id,slug,name,degree_level,official_program_url) values(?,'synthetic-seis','Synthetic programme','MASTER',?) returning id",
                Long.class,university,oulu.programUrl());
        jdbc.update("insert into catalog_release(id,country_code,enabled,approved_by,approved_at) values('synthetic-links','ZZ',true,'synthetic-reviewer',now())");
        jdbc.update("insert into catalog_membership(release_id,program_id,entry_level,qualification_pathway,education_oid,implementation_oid) values('synthetic-links',?,'MASTER','SINGLE_DEGREE',?,?)",
                program,oulu.educationOid(),oulu.implementationOid());
        transport.reset(SITEMAP,FakeTransport.text("application/xml","<urlset></urlset>"));
    }
    String url(){return jdbc.queryForObject("select official_program_url from degree_program where id=?",String.class,program);}
    String status(){return jdbc.queryForObject("select status from program_link_status where program_id=? and link_type='PROGRAM_PAGE'",String.class,program);}
    long history(){return jdbc.queryForObject("select count(*) from program_link_history where program_id=?",Long.class,program);}
    OfficialLinkMonitor.Outcome check(){return monitor.checkAll(NOW).getFirst();}
    static String sitemap(String... urls){
        StringBuilder b=new StringBuilder("<?xml version=\"1.0\"?><urlset>");
        for(String u:urls)b.append("<url><loc>").append(u).append("</loc></url>");
        return b.append("</urlset>").toString();
    }

    @Test void theReviewedPageIsVerifiedAndNothingChanges() {
        transport.reset(oulu.programUrl(),FakeTransport.html(page));
        assertThat(check().status()).isEqualTo("VERIFIED");
        assertThat(url()).isEqualTo(oulu.programUrl());
        assertThat(history()).isZero();
    }

    @Test void aMoveToTheSameReviewedProgramIsFollowedAndTheOldUrlIsKept() {
        String moved="https://www.oulu.fi/en/apply/masters-software-engineering-and-information-systems-2027";
        transport.reset(oulu.programUrl(),FakeTransport.redirect(moved));
        transport.reset(moved,FakeTransport.html(page));
        assertThat(check().status()).isEqualTo("REDIRECTED_VERIFIED");
        assertThat(url()).isEqualTo(moved);
        assertThat(jdbc.queryForObject("select old_url from program_link_history where program_id=?",String.class,program)).isEqualTo(oulu.programUrl());
    }

    @Test void aPageThatNoLongerShowsTheProgramIsReplacedOnlyByTheOneSitemapPageThatVerifies() {
        String right="https://www.oulu.fi/en/apply/masters-software-engineering-information-systems";
        String wrong="https://www.oulu.fi/en/apply/masters-software-engineering-something-else";
        transport.reset(oulu.programUrl(),FakeTransport.html("<html><body><h1>Study with us</h1></body></html>"));
        transport.reset(SITEMAP,FakeTransport.text("application/xml",sitemap(right,wrong,"https://www.oulu.fi/en/news/software")));
        transport.reset(right,FakeTransport.html(page));
        transport.reset(wrong,FakeTransport.html(page.replace("Master's in Software Engineering and Information Systems","Master's in Something Else")));
        assertThat(check().status()).isEqualTo("REPLACEMENT_VERIFIED");
        assertThat(url()).isEqualTo(right);
        assertThat(history()).isEqualTo(1);
    }

    @Test void twoVerifyingCandidatesWaitForAPersonAndTheOldUrlStays() {
        String a="https://www.oulu.fi/en/apply/masters-software-engineering-and-information-systems-a";
        String b="https://www.oulu.fi/en/apply/masters-software-engineering-and-information-systems-b";
        transport.reset(oulu.programUrl(),FakeTransport.html("<html><body><h1>Study with us</h1></body></html>"));
        transport.reset(SITEMAP,FakeTransport.text("application/xml",sitemap(a,b)));
        transport.reset(a,FakeTransport.html(page));
        transport.reset(b,FakeTransport.html(page));
        var outcome=check();
        assertThat(outcome.status()).isEqualTo("PENDING_REVIEW");
        assertThat(outcome.needsAttention()).isTrue();
        assertThat(url()).isEqualTo(oulu.programUrl());
        assertThat(history()).isZero();
    }

    @Test void a404IsBrokenOnlyOnItsSecondConsecutiveCheckAndTheUrlIsNeverRemoved() {
        transport.reset(oulu.programUrl(),FakeTransport.status(404));
        assertThat(check().status()).isEqualTo("SOURCE_UNAVAILABLE");
        var second=check();
        assertThat(second.status()).isEqualTo("BROKEN");
        assertThat(second.needsAttention()).isTrue();
        assertThat(url()).isEqualTo(oulu.programUrl());
        transport.reset(oulu.programUrl(),FakeTransport.html(page));
        assertThat(check().status()).isEqualTo("VERIFIED");
        assertThat(jdbc.queryForObject("select consecutive_failures from program_link_status where program_id=?",Integer.class,program)).isZero();
    }

    @Test void accessDeniedNeverBecomesBrokenOrDiscontinued() {
        transport.reset(oulu.programUrl(),FakeTransport.status(403));
        for(int i=0;i<3;i++) assertThat(check().status()).isEqualTo("SOURCE_UNAVAILABLE");
        assertThat(url()).isEqualTo(oulu.programUrl());
        assertThat(jdbc.queryForObject("select discontinued_at is null from degree_program where id=?",Boolean.class,program)).isTrue();
    }
}

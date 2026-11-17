package com.pathwayai.backend.provider.universitypages;

import com.pathwayai.backend.provider.studyinfo.FieldTaxonomy;
import com.pathwayai.backend.provider.studyinfo.StudyinfoAdapter;
import com.pathwayai.backend.provider.studyinfo.StudyinfoImporter;
import com.pathwayai.backend.provider.studyinfo.StudyinfoParser;
import com.pathwayai.backend.refresh.extract.ExtractedFact;
import com.pathwayai.backend.refresh.extract.PageCanonicalizer;
import com.pathwayai.backend.refresh.fetch.PageFetcher;
import com.pathwayai.backend.refresh.pipeline.ChangePipeline;
import com.pathwayai.backend.refresh.registry.SourceMonitor;
import com.pathwayai.backend.refresh.registry.SourceMonitorRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Explicit operator operation. Preview is read-only; applying requires its exact fingerprint and an existing V13 schema. */
@Component
@ConditionalOnNotWebApplication
@ConditionalOnProperty(name="app.curated.command")
public class CuratedCatalogCli implements ApplicationRunner {
    public static final String RELEASE="finland-curated-v1";
    record Offering(CuratedSources.Candidate candidate,StudyinfoParser.Program program,PageFetcher.Result studyinfo,
                    PageFetcher.Result university,List<ExtractedFact> facts,Long existingId) { }
    record Guidance(UniversityAdmissionsAdapter.SourceSpec spec,PageFetcher.Result response) { }
    private final CuratedSources candidates;
    private final UniversityAdmissionsAdapter admissions;
    private final PageFetcher fetcher;
    private final FieldTaxonomy taxonomy;
    private final JdbcClient jdbc;
    private final StudyinfoImporter importer;
    private final SourceMonitorRepository monitors;
    private final ChangePipeline pipeline;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final String command,expected;

    public CuratedCatalogCli(CuratedSources candidates,UniversityAdmissionsAdapter admissions,PageFetcher fetcher,
                             FieldTaxonomy taxonomy,JdbcClient jdbc,StudyinfoImporter importer,SourceMonitorRepository monitors,
                             ChangePipeline pipeline,Clock clock,PlatformTransactionManager transactions,
                             @Value("${app.curated.command}") String command,
                             @Value("${app.curated.expected-fingerprint:}") String expected) {
        this.candidates=candidates;this.admissions=admissions;this.fetcher=fetcher;this.taxonomy=taxonomy;this.jdbc=jdbc;
        this.importer=importer;this.monitors=monitors;this.pipeline=pipeline;this.clock=clock;
        this.transaction=new TransactionTemplate(transactions);this.command=command;this.expected=expected;
    }

    @Override public void run(ApplicationArguments args) {
        if(CuratedOperationsCli.COMMANDS.contains(command))return; // links/status: CuratedOperationsCli
        if(!List.of("preview","apply").contains(command))
            throw new IllegalArgumentException("Curated command must be preview, apply, "+String.join(", ",CuratedOperationsCli.COMMANDS));
        List<Offering> offerings=new ArrayList<>();
        List<Guidance> guidance=new ArrayList<>();
        for(var candidate:candidates.candidates()) {
            var studyinfo=read(importer.recordUrl(candidate.educationOid()));
            CuratedSources.validateStudyinfo(candidate,studyinfo.body());
            CuratedSources.validateStudyOption(candidate,studyinfo.body());
            var parsed=StudyinfoParser.parse(studyinfo.body(),taxonomy,candidate.implementationOid(),candidate.combined(),candidate.providerOid(),candidate.titleCodes());
            if(parsed.program()==null || !candidate.entryLevel().equals(parsed.program().degreeLevel())
                    || !parsed.program().languages().contains("en"))throw new IllegalStateException("Offering is not a verified English entry route: "+candidate.key());
            if(parsed.program().deadlines().stream().noneMatch(d->!LocalDate.parse(d.date()).isBefore(LocalDate.now(clock))))
                throw new IllegalStateException("No current/upcoming application round verified for selected implementation: "+candidate.key());
            var university=read(candidate.programUrl());
            var facts=UniversityProgramAdapter.extract(university.body(),university.finalUrl(),candidate);
            Long existing=importer.existingProgram(candidate.educationOid(),candidate.implementationOid()).orElse(null);
            offerings.add(new Offering(candidate,parsed.program(),studyinfo,university,facts,existing));
            System.out.println("CURATED_CANDIDATE "+candidate.key()+" education="+candidate.educationOid()+" implementation="
                    +candidate.implementationOid()+" entry="+candidate.entryLevel()+" existing="+existing+" universityFacts="+facts.size());
        }
        for(var source:admissions.sources()) {
            var response=read(source.url());
            for(var offering:offerings)if(UniversityAdmissionsAdapter.applies(source,offering.candidate()))
                UniversityAdmissionsAdapter.extract(response.body(),response.finalUrl(),source,offering.candidate());
            guidance.add(new Guidance(source,response));
        }
        String fingerprint=fingerprint(offerings,guidance);
        System.out.println("CURATED_PLAN release="+RELEASE+" bachelorEntry="+CuratedSources.PER_LEVEL+" master="+CuratedSources.PER_LEVEL+" total="+offerings.size()+" newPrograms="
                +offerings.stream().filter(o->o.existingId()==null).count()+" preservedPrograms="
                +jdbc.sql("select count(*) from degree_program").query(Long.class).single()+" fingerprint="+fingerprint);
        if(command.equals("preview")) {System.out.println("CURATED_PREVIEW no writes, no migration, no activation");return;}
        if(!fingerprint.equals(expected))throw new IllegalArgumentException("Plan differs from approved fingerprint; run and review a fresh preview");
        transaction.executeWithoutResult(status->apply(offerings,guidance));
        System.out.println("CURATED_APPLIED "+RELEASE+" "+CuratedSources.PER_LEVEL+" bachelor-entry + "+CuratedSources.PER_LEVEL+" master; no program/account/save was deleted");
    }

    private PageFetcher.Result read(String url) {
        var result=fetcher.fetch(url,null,null);
        if(result.kind()!=PageFetcher.Kind.OK)throw new IllegalStateException("Source not verified: "+url+" "+result.kind()+" "+result.error());
        return result;
    }

    private String fingerprint(List<Offering> offerings,List<Guidance> guidance) {
        List<String> rows=new ArrayList<>();
        for(var o:offerings) {
            rows.add(o.candidate().educationOid()+"|"+o.candidate().implementationOid()+"|"+o.program().name()+"|"+o.program().degreeLevel());
            StudyinfoParser.toFacts(o.program()).forEach(f->rows.add(f.type()+f.key()+f.canonicalValue()));
            o.facts().forEach(f->rows.add(f.type()+f.key()+f.canonicalValue()));
            for(var g:guidance)if(UniversityAdmissionsAdapter.applies(g.spec(),o.candidate()))
                UniversityAdmissionsAdapter.extract(g.response().body(),g.response().finalUrl(),g.spec(),o.candidate())
                        .forEach(f->rows.add(f.type()+f.key()+f.canonicalValue()));
        }
        rows.sort(String::compareTo);
        return PageCanonicalizer.sha256(String.join("\n",rows));
    }

    private void apply(List<Offering> offerings,List<Guidance> guidance) {
        Instant now=clock.instant();
        jdbc.sql("select pg_advisory_xact_lock(721681203)").query((rs,n)->1).single();
        jdbc.sql("insert into catalog_release(id,country_code) values(:id,'FI') on conflict do nothing").param("id",RELEASE).update();
        Map<String,Long> ids=new LinkedHashMap<>();
        for(var o:offerings) {
            long id=importer.importReviewed(o.candidate(),o.program(),o.studyinfo(),now);
            ids.put(o.candidate().educationOid(),id);
            long university=jdbc.sql("select university_id from degree_program where id=:p").param("p",id).query(Long.class).single();
            var studyMonitor=register(importer.recordUrl(o.candidate().educationOid()),"Studyinfo official offering", "Opetushallitus",
                    "GOVERNMENT",university,id,StudyinfoAdapter.NAME,StudyinfoAdapter.VERSION,now);
            publish(studyMonitor,StudyinfoParser.toFacts(o.program()),o.studyinfo(),now,false);
            var universityMonitor=register(o.candidate().programUrl(),o.candidate().heading(),publisher(o.candidate().university()),
                    "UNIVERSITY_WEBSITE",university,id,UniversityProgramAdapter.NAME,"1",now);
            publish(universityMonitor,o.facts(),o.university(),now,true);
            jdbc.sql("""
                    insert into catalog_membership(release_id,program_id,entry_level,qualification_pathway,education_oid,implementation_oid,study_option)
                    values(:r,:p,:level,:pathway,:oid,:implementation,:option) on conflict(release_id,program_id) do update
                    set entry_level=excluded.entry_level,qualification_pathway=excluded.qualification_pathway,
                    education_oid=excluded.education_oid,implementation_oid=excluded.implementation_oid,study_option=excluded.study_option""")
                    .param("r",RELEASE).param("p",id).param("level",o.candidate().entryLevel())
                    .param("pathway",o.candidate().combined()?"BACHELOR_AND_MASTER":"SINGLE_DEGREE")
                    .param("oid",o.candidate().educationOid()).param("implementation",o.candidate().implementationOid())
                    .param("option",CuratedSources.validateStudyOption(o.candidate(),o.studyinfo().body())).update();
        }
        for(var g:guidance) {
            Long university=jdbc.sql("select id from university where slug=:slug").param("slug",g.spec().university()).query(Long.class).single();
            var monitor=register(g.spec().url(),g.spec().heading(),publisher(g.spec().university()),"UNIVERSITY_WEBSITE",
                    university,null,UniversityAdmissionsAdapter.NAME,"1",now);
            for(var o:offerings)if(UniversityAdmissionsAdapter.applies(g.spec(),o.candidate())) {
                long id=ids.get(o.candidate().educationOid());
                var facts=UniversityAdmissionsAdapter.extract(g.response().body(),g.response().finalUrl(),g.spec(),o.candidate());
                com.pathwayai.backend.program.service.UniversityFacts.requireContinuity(jdbc,monitor.sourceId(),id,facts);
                var result=pipeline.process(monitor.withProgramId(id),facts,"curated-1+university-admissions@1",now,true);
                if(result.rejectedCount()>0 || result.pendingReview()>0)throw new IllegalStateException("Guidance conflicts need review before activation");
            }
            success(monitor,g.response(),now);
        }
        var balance=jdbc.sql("select entry_level,count(*) n from catalog_membership where release_id=:r group by entry_level")
                .param("r",RELEASE).query().listOfRows();
        if(balance.size()!=2 || balance.stream().anyMatch(r->((Number)r.get("n")).intValue()!=CuratedSources.PER_LEVEL))
            throw new IllegalStateException("Activation requires exactly "+CuratedSources.PER_LEVEL+" distinct offerings at each entry level");
        jdbc.sql("""
                update catalog_release set enabled=true,approved_by='operator:reviewed-fingerprint',approved_at=:now where id=:r""")
                .param("now",Timestamp.from(now)).param("r",RELEASE).update();
    }

    private SourceMonitor register(String url,String title,String publisher,String type,Long university,Long program,
                                   String adapter,String version,Instant now) {
        long source=jdbc.sql("""
                insert into source(url,title,publisher,source_type,retrieved_on) values(:url,:title,:publisher,:type,:today)
                on conflict(url) do update set retrieved_on=excluded.retrieved_on returning id""")
                .param("url",url).param("title",title.length()>300?title.substring(0,300):title).param("publisher",publisher)
                .param("type",type).param("today",LocalDate.ofInstant(now,ZoneOffset.UTC)).query(Long.class).single();
        monitors.register(source,new SourceMonitor.Spec(url,university,program,"PROGRAM","curated facts","ADAPTER",adapter,version,7),"VERIFIED",now);
        var monitor=monitors.findBySourceId(source).orElseThrow();
        if(!adapter.equals(monitor.adapterName()) || !java.util.Objects.equals(program,monitor.programId())
                || !java.util.Objects.equals(university,monitor.universityId()))throw new IllegalStateException("Source monitor association conflict: "+url);
        var updated=monitor.with(m->m.adapterVersion=version);
        monitors.saveState(updated);
        return updated;
    }

    private void publish(SourceMonitor monitor,List<ExtractedFact> facts,PageFetcher.Result response,Instant now,boolean reviewedNewFacts) {
        if(monitor.adapterName().equals("university-program"))
            com.pathwayai.backend.program.service.UniversityFacts.requireContinuity(jdbc,monitor.sourceId(),monitor.programId(),facts);
        var result=pipeline.process(monitor,facts,"curated-1+"+monitor.adapterName()+"@"+monitor.adapterVersion(),now,reviewedNewFacts);
        if(result.rejectedCount()>0 || result.pendingReview()>0)throw new IllegalStateException("Changed facts require review before curated activation");
        success(monitor,response,now);
    }

    private void success(SourceMonitor monitor,PageFetcher.Result response,Instant now) {
        String hash=new PageCanonicalizer().canonicalize(response.body(),response.contentType()).hash();
        monitors.saveState(monitor.with(m->{m.status="ACTIVE";m.failureCount=0;m.unsupportedReason=null;m.lastAttemptAt=now;m.lastSuccessAt=now;
            m.nextCheckAt=now.plus(java.time.Duration.ofDays(7));m.lastResult="BASELINE";m.contentHash=hash;
            m.hashParserVersion="curated-1+"+monitor.adapterName()+"@"+monitor.adapterVersion();}));
        monitors.logCheck(monitor.sourceId(),now,"BASELINE",response.httpStatus(),hash,response.durationMs(),"curated-1",null);
    }
    private static String publisher(String university){return CuratedSources.publisher(university);}
}

package com.pathwayai.backend.program.service;

import com.pathwayai.backend.program.dto.ProgramDetailResponse.SourceRef;
import com.pathwayai.backend.source.entity.SourceType;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Separate provenance and freshness for university information; Studyinfo success never refreshes these facts. */
@Component
public class UniversityFacts {
    public static final Set<String> FIELDS=Set.of("qualification","credits","duration_structure","study_mode","campus",
            "eligibility","application_period","tuition_scope","scholarship","overview","structure","specializations",
            "documents","selection","language_tests","entrance_tests","instructions","financial_url","curriculum_url",
            "official_program_url","official_admissions_url");
    /** applicantCategory/academicYear are null unless the source states them; an undated fee stays UNKNOWN_YEAR. */
    public record Fact(String field,String scope,String value,String applicationCycle,String temporalStatus,
                       String verificationStatus,Instant lastSuccessfulCheck,SourceRef source,String applicantCategory,String academicYear) { }
    public record Check(String url,String publisher,String status,Instant lastAttemptAt,Instant lastSuccessAt,
                        Instant nextCheckAt,String method) { }
    /** qualificationPathway/studyOption: from the curated membership (null outside it). */
    public record Information(List<Fact> facts,List<Check> sources,String qualificationPathway,String studyOption) { }
    private final JdbcClient jdbc;
    private final CuratedCatalog catalog;
    private final Clock clock;

    public UniversityFacts(JdbcClient jdbc,CuratedCatalog catalog,Clock clock) {
        this.jdbc=jdbc; this.catalog=catalog; this.clock=clock;
    }

    public Information forProgram(long id) {
        if (!catalog.schemaExists()) return new Information(List.of(),List.of(),null,null);
        var facts=jdbc.sql("""
                select f.*,s.url,s.title,s.publisher,s.source_type,s.retrieved_on,m.last_success_at,m.status,m.last_result,
                  exists(select 1 from staged_change c where c.program_id=f.program_id and c.fact_type='UNIVERSITY_FACT'
                    and c.status='PENDING_REVIEW' and c.fact_key=f.field_name||'|'||f.scope_key) as pending
                from program_university_fact f join source s on s.id=f.source_id
                left join source_monitor m on m.source_id=f.source_id where f.program_id=:p order by f.field_name,f.scope_key""")
                .param("p",id).query((rs,n)-> {
                    Instant last=rs.getTimestamp("last_success_at")==null ? rs.getTimestamp("checked_at").toInstant()
                            : rs.getTimestamp("last_success_at").toInstant();
                    String status=rs.getBoolean("pending") ? "PENDING_REVIEW"
                            : last.isBefore(clock.instant().minus(java.time.Duration.ofDays(45)))
                            || rs.getString("status")!=null && !"ACTIVE".equals(rs.getString("status"))
                            || java.util.Set.of("FAILED","BLOCKED","RATE_LIMITED").contains(rs.getString("last_result")==null?"":rs.getString("last_result")) ? "STALE" : "VERIFIED";
                    String cycle=rs.getString("application_cycle");
                    String temporal="UNKNOWN_YEAR";
                    if (cycle!=null && cycle.matches("\\d{4}")) temporal=Integer.parseInt(cycle)<LocalDate.now(clock).getYear() ? "HISTORICAL":"CURRENT";
                    if ("application_period".equals(rs.getString("field_name"))) {
                        var end=com.pathwayai.backend.provider.universitypages.UniversityProgramAdapter.periodEnd(rs.getString("value"));
                        if (end!=null) temporal=end.isBefore(LocalDate.now(clock)) ? "HISTORICAL":"CURRENT";
                    }
                    String year=rs.getString("academic_year");
                    if (year!=null) temporal=Integer.parseInt(year.substring(5))<=LocalDate.now(clock).getYear()
                            && LocalDate.now(clock).isAfter(LocalDate.of(Integer.parseInt(year.substring(5)),7,31)) ? "HISTORICAL":"CURRENT";
                    return new Fact(rs.getString("field_name"),rs.getString("scope_key"),rs.getString("value"),cycle,
                            temporal,status,last,new SourceRef(rs.getString("url"),rs.getString("title"),rs.getString("publisher"),
                            SourceType.valueOf(rs.getString("source_type")),rs.getDate("retrieved_on").toLocalDate()),
                            rs.getString("applicant_category"),year);
                }).list();
        var sources=jdbc.sql("""
                select s.url,s.publisher,case when m.last_result in ('FAILED','BLOCKED','RATE_LIMITED') then 'FAILING' else m.status end,
                  m.last_attempt_at,m.last_success_at,m.next_check_at,m.adapter_name
                from source_monitor m join source s on s.id=m.source_id
                where (m.program_id=:p or exists(select 1 from program_source ps where ps.program_id=:p and ps.source_id=s.id))
                  and m.adapter_name in ('university-program','university-admissions') order by s.url""")
                .param("p",id).query((rs,n)->new Check(rs.getString(1),rs.getString(2),rs.getString(3),
                        instant(rs.getTimestamp(4)),instant(rs.getTimestamp(5)),instant(rs.getTimestamp(6)),rs.getString(7))).list();
        var curated=jdbc.sql("""
                select m.qualification_pathway,m.study_option from catalog_membership m join catalog_release r on r.id=m.release_id
                where r.enabled and m.program_id=:p""").param("p",id).query((rs,n)->new String[]{rs.getString(1),rs.getString(2)}).optional();
        return new Information(facts,sources,curated.map(c->c[0]).orElse(null),curated.map(c->c[1]).orElse(null));
    }

    private static Instant instant(java.sql.Timestamp value) {return value==null?null:value.toInstant();}

    /** A page that stops stating a published evergreen fact cannot refresh that fact merely by returning HTTP 200. */
    public static void requireContinuity(JdbcClient jdbc,long source,long program,
                                         List<com.pathwayai.backend.refresh.extract.ExtractedFact> extracted) {
        var seen=extracted.stream().filter(f->f.type()==com.pathwayai.backend.refresh.extract.ExtractedFact.FactType.UNIVERSITY_FACT)
                .map(com.pathwayai.backend.refresh.extract.ExtractedFact::key).collect(java.util.stream.Collectors.toSet());
        var previous=jdbc.sql("select field_name||'|'||scope_key from program_university_fact where source_id=:s and program_id=:p and field_name<>'application_period'")
                .param("s",source).param("p",program).query(String.class).list();
        if(!seen.containsAll(previous))throw new IllegalStateException("University source no longer states every published fact; review required, previous facts retained");
    }
}

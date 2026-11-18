package com.pathwayai.backend.provider.universitypages;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Unattended curated-catalogue operations, run by scripts/ci/run-refresh.sh:
 * <ul><li>{@code links}: check every official destination ({@link OfficialLinkMonitor}); results are stored first,
 * then the process FAILS when any link needs a person, so the workflow's alert issue fires.</li>
 * <li>{@code status}: read-only operator report (selection, freshness, failing sources, links, review queue,
 * upcoming deadlines).</li></ul>
 * Neither command exists over HTTP.
 */
@Component
@ConditionalOnNotWebApplication
@ConditionalOnProperty(name="app.curated.command")
public class CuratedOperationsCli implements ApplicationRunner {
    static final List<String> COMMANDS=List.of("links","status");
    private final OfficialLinkMonitor links;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final String command;

    public CuratedOperationsCli(OfficialLinkMonitor links,JdbcClient jdbc,Clock clock,@Value("${app.curated.command}") String command) {
        this.links=links;this.jdbc=jdbc;this.clock=clock;this.command=command;
    }

    @Override public void run(ApplicationArguments args) {
        if(!COMMANDS.contains(command)) return;
        if(!releaseEnabled()) { System.out.println("CURATED_"+command.toUpperCase()+" no enabled curated release: nothing to do"); return; }
        if(command.equals("links")) {
            var outcomes=links.checkAll(clock.instant());
            outcomes.forEach(o->System.out.println("LINK "+o.status()+" program="+o.programId()+" "+o.linkType()+" "+o.url()
                    +(o.detail()==null?"":" | "+o.detail())));
            long attention=outcomes.stream().filter(OfficialLinkMonitor.Outcome::needsAttention).count();
            System.out.println("CURATED_LINKS checked="+outcomes.size()+" needAttention="+attention);
            if(attention>0) throw new IllegalStateException(attention+" official link(s) need review: see LINK lines above");
            return;
        }
        report();
    }

    private boolean releaseEnabled() {
        return jdbc.sql("select to_regclass('public.catalog_release') is not null").query(Boolean.class).single()
                && jdbc.sql("select exists(select 1 from catalog_release where enabled)").query(Boolean.class).single();
    }

    private void report() {
        String curated="select m.program_id from catalog_membership m join catalog_release r on r.id=m.release_id where r.enabled";
        System.out.println("== Curated catalogue status "+clock.instant());
        jdbc.sql("select entry_level,count(*) from catalog_membership m join catalog_release r on r.id=m.release_id where r.enabled group by 1 order by 1")
                .query((rs,n)->rs.getString(1)+"="+rs.getLong(2)).list().forEach(l->System.out.println("selected "+l));
        System.out.println("last successful source check: "+jdbc.sql("select max(m.last_success_at) from source_monitor m where m.program_id in ("+curated+")")
                .query(String.class).optional().orElse("never"));
        System.out.println("programs whose Studyinfo record was not read within 10 days: "+jdbc.sql(
                "select count(*) from ("+curated+") c where not exists (select 1 from source_monitor m where m.program_id=c.program_id"
                + " and m.adapter_name='studyinfo-external' and m.last_success_at > now() - interval '10 days')").query(Long.class).single());
        System.out.println("university facts older than 45 days: "+jdbc.sql("select count(*) from program_university_fact where program_id in ("
                +curated+") and checked_at < now() - interval '45 days'").query(Long.class).single());
        jdbc.sql("select s.url,m.status,m.last_result,m.failure_count from source_monitor m join source s on s.id=m.source_id where m.program_id in ("
                +curated+") and (m.status<>'ACTIVE' or m.last_result in ('FAILED','BLOCKED','RATE_LIMITED')) order by s.url")
                .query((rs,n)->"failing source: "+rs.getString(1)+" "+rs.getString(2)+"/"+rs.getString(3)+" failures="+rs.getInt(4))
                .list().forEach(System.out::println);
        jdbc.sql("select status,count(*) from program_link_status where program_id in ("+curated+") group by 1 order by 1")
                .query((rs,n)->"links "+rs.getString(1)+"="+rs.getLong(2)).list().forEach(System.out::println);
        jdbc.sql("select program_id,link_type,url,detail from program_link_status where program_id in ("+curated
                +") and status in ('BROKEN','STALE','PENDING_REVIEW') order by program_id")
                .query((rs,n)->"link needs review: program="+rs.getLong(1)+" "+rs.getString(2)+" "+rs.getString(3)+" | "+rs.getString(4))
                .list().forEach(System.out::println);
        System.out.println("pending review items: "+jdbc.sql("select count(*) from staged_change where status='PENDING_REVIEW' and program_id in ("
                +curated+")").query(Long.class).single());
        jdbc.sql("select p.slug,i.label,i.application_deadline from program_intake i join degree_program p on p.id=i.program_id where i.program_id in ("
                +curated+") and i.application_deadline >= :today order by i.application_deadline,p.slug limit 25")
                .param("today",LocalDate.now(clock)).query((rs,n)->"upcoming deadline "+rs.getDate(3)+" "+rs.getString(1)+" | "+rs.getString(2))
                .list().forEach(System.out::println);
        detailLines().forEach(System.out::println);
    }

    /**
     * Itemised, read-only evidence of what the curated programs publish (published catalogue data only: no account,
     * profile or saved-program data is read). A fee without a source-stated academic year shows "not stated".
     */
    List<String> detailLines() {
        String curated="select m.program_id from catalog_membership m join catalog_release r on r.id=m.release_id where r.enabled";
        List<String> out=new java.util.ArrayList<>();
        out.add("== Curated data detail");
        out.addAll(jdbc.sql("select p.slug,coalesce(af.slug,'none') from degree_program p left join academic_field af on af.id=p.academic_field_id"
                +" where p.id in ("+curated+") order by p.slug").query((rs,n)->"field "+rs.getString(1)+" = "+rs.getString(2)).list());
        out.add("tuition rows on curated programs: "+jdbc.sql("select count(*) from tuition_fee where program_id in ("+curated+")").query(Long.class).single());
        out.add("tuition rows with an academic year: "+jdbc.sql("select count(*) from tuition_fee where academic_year is not null and program_id in ("
                +curated+")").query(Long.class).single());
        out.addAll(jdbc.sql("select p.slug,t.student_category,t.amount,t.currency,t.billing_period,coalesce(t.academic_year,'not stated'),s.url"
                +" from tuition_fee t join degree_program p on p.id=t.program_id join source s on s.id=t.source_id"
                +" where t.program_id in ("+curated+") order by p.slug,t.student_category")
                .query((rs,n)->"tuition "+rs.getString(1)+" | "+rs.getString(2)+" | "+rs.getBigDecimal(3).stripTrailingZeros().toPlainString()+" "+rs.getString(4)
                        +" | billing "+rs.getString(5)+" | academic year: "+rs.getString(6)+" | source: "+rs.getString(7)).list());
        out.addAll(jdbc.sql("select field_name,scope_key,count(*),count(distinct program_id) from program_university_fact where program_id in ("+curated
                +") group by 1,2 order by 1,2").query((rs,n)->"university facts "+rs.getString(1)+"/"+(rs.getString(2).isEmpty()?"(page)":rs.getString(2))
                +" = "+rs.getLong(3)+" rows on "+rs.getLong(4)+" programs").list());
        return out;
    }
}

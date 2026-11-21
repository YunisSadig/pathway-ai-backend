package com.pathwayai.backend.provider.universitypages;

import com.pathwayai.backend.refresh.fetch.PageFetcher;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Checks the official destinations of every curated program and repairs moved program pages only when the repair is
 * certain. A destination is valid only when the page still IS the reviewed program (exactly one matching heading, the
 * university's program path, a consistent degree): an HTTP 200 alone proves nothing. A 403, robots block, timeout or
 * server error never marks a link broken (SOURCE_UNAVAILABLE); a 404/410 becomes BROKEN on its second consecutive
 * check. A replacement is applied only when exactly one page from the university's own sitemap passes the identity
 * check; otherwise the link waits for review. URLs are never constructed (no "/2026/" -> "/2027/" guessing), the old
 * URL is kept in {@code program_link_history}, and nothing is ever deleted.
 */
@Component
public class OfficialLinkMonitor {
    public record Outcome(long programId,String linkType,String url,String status,String detail) {
        public boolean needsAttention(){return Set.of("BROKEN","PENDING_REVIEW","STALE").contains(status);}
    }
    static final int BROKEN_AFTER=2;
    static final int MAX_SITEMAPS=6;
    static final int MAX_CANDIDATES=3;
    private static final Pattern LOC=Pattern.compile("<loc>\\s*([^<\\s]+)\\s*</loc>");
    private static final Set<String> STOP=Set.of("bachelor","bachelors","master","masters","programme","program","degree",
            "science","technology","and","the","for","with","university","in","of");

    private final PageFetcher fetcher;
    private final CuratedSources sources;
    private final JdbcClient jdbc;

    public OfficialLinkMonitor(PageFetcher fetcher,CuratedSources sources,JdbcClient jdbc) {
        this.fetcher=fetcher;this.sources=sources;this.jdbc=jdbc;
    }

    /** One pass over the enabled curated release. Each program's result is written before the next is checked. */
    public List<Outcome> checkAll(Instant now) {
        var rows=jdbc.sql("""
                select m.program_id,m.education_oid,p.official_program_url,p.official_admissions_url
                from catalog_membership m join catalog_release r on r.id=m.release_id join degree_program p on p.id=m.program_id
                where r.enabled order by m.program_id""")
                .query((rs,n)->new Object[]{rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4)}).list();
        List<Outcome> out=new ArrayList<>();
        for(Object[] row:rows) {
            long program=(Long)row[0];
            var candidate=sources.byEducationOid((String)row[1]);
            if(candidate==null) {
                out.add(record(program,"PROGRAM_PAGE",(String)row[2],"PENDING_REVIEW",null,null,"no reviewed association for this education",now));
                continue;
            }
            String programUrl=row[2]==null?candidate.programUrl():(String)row[2];
            out.add(checkProgramPage(program,candidate,programUrl,now));
            if(row[3]!=null) out.add(checkAdmissionsPage(program,candidate,(String)row[3],now));
        }
        return out;
    }

    Outcome checkProgramPage(long program,CuratedSources.Candidate candidate,String url,Instant now) {
        var r=fetcher.fetch(url,null,null);
        switch(r.kind()) {
            case OK -> {
                if(identity(r.body(),r.finalUrl(),candidate)) {
                    if(r.finalUrl().equals(url)) return record(program,"PROGRAM_PAGE",url,"VERIFIED",r.finalUrl(),null,null,now);
                    replace(program,"PROGRAM_PAGE",url,r.finalUrl(),"permanent move followed: the new page is the same reviewed program",now);
                    return record(program,"PROGRAM_PAGE",r.finalUrl(),"REDIRECTED_VERIFIED",r.finalUrl(),null,"moved from "+url,now);
                }
                return discover(program,candidate,url,"STALE","page no longer shows the reviewed program (final "+r.finalUrl()+")",now,null);
            }
            case NOT_MODIFIED -> {return record(program,"PROGRAM_PAGE",url,"VERIFIED",url,null,null,now);}
            case FAILED -> {
                if(r.httpStatus()==404 || r.httpStatus()==410) {
                    int failures=failures(program,"PROGRAM_PAGE")+1;
                    if(failures<BROKEN_AFTER)
                        return record(program,"PROGRAM_PAGE",url,"SOURCE_UNAVAILABLE",null,null,"HTTP "+r.httpStatus()+" once; rechecked next run",now,failures);
                    return discover(program,candidate,url,"BROKEN","HTTP "+r.httpStatus()+" on "+failures+" consecutive checks",now,failures);
                }
                return record(program,"PROGRAM_PAGE",url,"SOURCE_UNAVAILABLE",null,null,r.error(),now);
            }
            default -> {return record(program,"PROGRAM_PAGE",url,"SOURCE_UNAVAILABLE",null,null,r.kind()+": "+r.error(),now);}
        }
    }

    Outcome checkAdmissionsPage(long program,CuratedSources.Candidate candidate,String url,Instant now) {
        var r=fetcher.fetch(url,null,null);
        if(r.kind()==PageFetcher.Kind.OK || r.kind()==PageFetcher.Kind.NOT_MODIFIED) {
            String fin=r.finalUrl()==null?url:r.finalUrl();
            if(!CuratedSources.owned(fin,candidate.university()))
                return record(program,"ADMISSIONS_PAGE",url,"PENDING_REVIEW",fin,null,"redirect leaves the university's English pages",now);
            return record(program,"ADMISSIONS_PAGE",url,fin.equals(url)?"VERIFIED":"REDIRECTED_VERIFIED",fin,null,null,now);
        }
        if(r.kind()==PageFetcher.Kind.FAILED && (r.httpStatus()==404 || r.httpStatus()==410)) {
            int failures=failures(program,"ADMISSIONS_PAGE")+1;
            return record(program,"ADMISSIONS_PAGE",url,failures<BROKEN_AFTER?"SOURCE_UNAVAILABLE":"BROKEN",null,null,
                    "HTTP "+r.httpStatus()+(failures<BROKEN_AFTER?" once; rechecked next run":" on "+failures+" consecutive checks"),now,failures);
        }
        return record(program,"ADMISSIONS_PAGE",url,"SOURCE_UNAVAILABLE",null,null,r.kind()+": "+r.error(),now);
    }

    /** Looks for the program in the university's own sitemap; applies a replacement only when exactly one page verifies. */
    private Outcome discover(long program,CuratedSources.Candidate candidate,String url,String failedStatus,String why,Instant now,Integer failures) {
        List<String> verified=new ArrayList<>();
        List<String> tried=new ArrayList<>();
        for(String candidateUrl:sitemapCandidates(candidate)) {
            if(candidateUrl.equals(url)) continue;
            tried.add(candidateUrl);
            var r=fetcher.fetch(candidateUrl,null,null);
            if(r.kind()==PageFetcher.Kind.OK && identity(r.body(),r.finalUrl(),candidate)) verified.add(r.finalUrl());
        }
        var distinct=new LinkedHashSet<>(verified);
        if(distinct.size()==1) {
            String replacement=distinct.iterator().next();
            replace(program,"PROGRAM_PAGE",url,replacement,why+"; the only sitemap page that verifies as the reviewed program",now);
            return record(program,"PROGRAM_PAGE",replacement,"REPLACEMENT_VERIFIED",replacement,null,"replaced "+url,now);
        }
        String status=failedStatus.equals("BROKEN")?"BROKEN":"PENDING_REVIEW";
        String detail=why+(distinct.isEmpty()?"; no verified replacement among "+tried.size()+" sitemap candidates"
                :"; "+distinct.size()+" pages verify, a person must choose");
        return record(program,"PROGRAM_PAGE",url,status,null,distinct.isEmpty()?null:String.join(" ",distinct),detail,now,failures);
    }

    /** Program-path URLs from the university's sitemap(s), best name match first. Read with the same crawl rules. */
    List<String> sitemapCandidates(CuratedSources.Candidate candidate) {
        var profile=UniversityProgramAdapter.PROFILES.get(candidate.university());
        String host=URI.create(candidate.programUrl()).getHost();
        List<String> pending=new ArrayList<>(List.of("https://"+host+"/sitemap.xml"));
        Set<String> pages=new LinkedHashSet<>();
        int read=0;
        while(!pending.isEmpty() && read<MAX_SITEMAPS) {
            var r=fetcher.fetch(pending.removeFirst(),null,null);
            read++;
            if(r.kind()!=PageFetcher.Kind.OK) continue;
            boolean index=r.body().contains("<sitemapindex");
            var m=LOC.matcher(r.body());
            while(m.find()) {
                String loc=m.group(1).replace("&amp;","&");
                if(index) { if(loc.contains("/en") || !loc.matches(".*/(fi|sv)[/-].*")) pending.add(loc); }
                else if(UniversityProgramAdapter.isProgramPage(loc,candidate.university())) pages.add(loc);
            }
        }
        Set<String> want=tokens(candidate.heading());
        return pages.stream().map(p->new Object[]{p,overlap(tokens(URI.create(p).getPath()),want)})
                .filter(o->(int)o[1]>=Math.min(2,want.size()))
                .sorted(Comparator.comparingInt((Object[] o)->-(int)o[1]).thenComparing(o->(String)o[0]))
                .limit(MAX_CANDIDATES).map(o->(String)o[0]).toList();
    }

    private static Set<String> tokens(String s) {
        Set<String> t=new LinkedHashSet<>();
        for(String w:s.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) if(w.length()>=2 && !STOP.contains(w)) t.add(w);
        return t;
    }
    private static int overlap(Set<String> a,Set<String> b){int n=0;for(String x:a)if(b.contains(x))n++;return n;}

    static boolean identity(String html,String finalUrl,CuratedSources.Candidate candidate) {
        try { UniversityProgramAdapter.extract(html,finalUrl,candidate); return true; }
        catch(IllegalArgumentException e) { return false; }
    }

    public int failures(long program,String type) {
        return jdbc.sql("select consecutive_failures from program_link_status where program_id=:p and link_type=:t")
                .param("p",program).param("t",type).query(Integer.class).optional().orElse(0);
    }

    private void replace(long program,String type,String oldUrl,String newUrl,String reason,Instant now) {
        String column=type.equals("PROGRAM_PAGE")?"official_program_url":"official_admissions_url";
        jdbc.sql("insert into program_link_history(program_id,link_type,old_url,new_url,reason,changed_at,changed_by) values(:p,:t,:o,:n,:r,:now,'system:link-monitor')")
                .param("p",program).param("t",type).param("o",oldUrl).param("n",newUrl).param("r",reason.length()>500?reason.substring(0,500):reason)
                .param("now",Timestamp.from(now)).update();
        jdbc.sql("update degree_program set "+column+"=:n,updated_at=:now where id=:p")
                .param("n",newUrl).param("now",Timestamp.from(now)).param("p",program).update();
        jdbc.sql("update program_university_fact set value=:n,checked_at=:now where program_id=:p and field_name=:f")
                .param("n",newUrl).param("now",Timestamp.from(now)).param("p",program).param("f",column).update();
    }

    private Outcome record(long program,String type,String url,String status,String finalUrl,String candidateUrl,String detail,Instant now) {
        return record(program,type,url,status,finalUrl,candidateUrl,detail,now,status.endsWith("VERIFIED")?Integer.valueOf(0):null);
    }

    /** {@code failures}: consecutive 404/410 count after this check; null keeps the previous count (403, timeout...). */
    public Outcome record(long program,String type,String url,String status,String finalUrl,String candidateUrl,String detail,Instant now,Integer failures) {
        boolean ok=status.endsWith("VERIFIED");
        String d=detail==null?null:detail.length()>500?detail.substring(0,500):detail;
        jdbc.sql("""
                insert into program_link_status(program_id,link_type,url,status,final_url,candidate_url,detail,consecutive_failures,last_checked_at,last_verified_at)
                values(:p,:t,:u,:s,:f,:c,:d,:fails,:now,:verified)
                on conflict(program_id,link_type) do update set url=excluded.url,status=excluded.status,final_url=excluded.final_url,
                  candidate_url=excluded.candidate_url,detail=excluded.detail,consecutive_failures=excluded.consecutive_failures,
                  last_checked_at=excluded.last_checked_at,
                  last_verified_at=coalesce(excluded.last_verified_at,program_link_status.last_verified_at)""")
                .param("p",program).param("t",type).param("u",url).param("s",status).param("f",finalUrl).param("c",candidateUrl)
                .param("d",d).param("fails",failures!=null?failures:failures(program,type))
                .param("now",Timestamp.from(now)).param("verified",ok?Timestamp.from(now):null).update();
        return new Outcome(program,type,url,status,d);
    }
}

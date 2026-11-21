package com.pathwayai.backend.provider.polon;

import com.pathwayai.backend.provider.universitypages.OfficialLinkMonitor;
import com.pathwayai.backend.provider.universitypages.OfficialLinkMonitor.Outcome;
import com.pathwayai.backend.refresh.fetch.PageFetcher;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Health of the official destinations of the Polish programmes, as the Finnish {@link OfficialLinkMonitor} does for Finland and
 * stored in the same table. A destination is VERIFIED only when the page still IS the reviewed programme (the same exactly-one-h1
 * identity rule used when the link was stored): an HTTP 200 alone proves nothing. A 403, robots block, timeout or server error never
 * marks a link broken (SOURCE_UNAVAILABLE); a 404/410 becomes BROKEN on its second consecutive check. Polish links are never
 * repaired or replaced automatically (no sitemap guessing, no constructed URLs): a broken or changed link waits for a person, and
 * the stored URL stays untouched until then.
 */
@Component
public class PolishLinkMonitor {
    private final PageFetcher fetcher;
    private final PolonSources sources;
    private final OfficialLinkMonitor store;
    private final JdbcClient jdbc;

    public PolishLinkMonitor(PageFetcher fetcher, PolonSources sources, OfficialLinkMonitor store, JdbcClient jdbc) {
        this.fetcher = fetcher;
        this.sources = sources;
        this.store = store;
        this.jdbc = jdbc;
    }

    /** One pass over every Polish programme that has an official programme or admissions page. Each result is written before the next check. */
    public List<Outcome> checkAll(Instant now) {
        var rows = jdbc.sql("""
                select p.id, e.value, p.official_program_url, p.official_admissions_url
                from degree_program p join program_external_id e on e.program_id = p.id and e.scheme = :scheme
                where p.official_program_url is not null or p.official_admissions_url is not null order by p.id""")
                .param("scheme", PolonParser.SCHEME_COURSE)
                .query((rs, n) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)}).list();
        List<Outcome> out = new ArrayList<>();
        for (String[] row : rows) {
            long program = Long.parseLong(row[0]);
            var candidate = sources.byCourseUuid(row[1]);
            if (candidate.isEmpty()) {
                out.add(store.record(program, "PROGRAM_PAGE", row[2], "PENDING_REVIEW", null, null, "no reviewed association for this programme", now, null));
                continue;
            }
            if (row[2] != null) out.add(check(program, candidate.get(), "PROGRAM_PAGE", row[2], now));
            if (row[3] != null) out.add(check(program, candidate.get(), "ADMISSIONS_PAGE", row[3], now));
        }
        return out;
    }

    Outcome check(long program, PolonSources.Candidate candidate, String type, String url, Instant now) {
        var r = fetcher.fetch(url, null, null);
        switch (r.kind()) {
            case OK -> {
                String fin = r.finalUrl() == null ? url : r.finalUrl();
                if (!PolonSources.owned(fin, candidate.university()))
                    return store.record(program, type, url, "PENDING_REVIEW", fin, null, "redirect leaves the university's own domain", now, null);
                if (type.equals("PROGRAM_PAGE") && !stillTheProgramme(r.body(), fin, candidate.programUrl(), candidate))
                    return store.record(program, type, url, "STALE", fin, null, "page no longer shows the reviewed programme (final " + fin + ")", now, null);
                return store.record(program, type, url, fin.equals(url) ? "VERIFIED" : "REDIRECTED_VERIFIED", fin, null,
                        fin.equals(url) ? null : "redirected to " + fin + "; stored URL unchanged until a person confirms", now, 0);
            }
            case NOT_MODIFIED -> {
                return store.record(program, type, url, "VERIFIED", url, null, null, now, 0);
            }
            case FAILED -> {
                if (r.httpStatus() == 404 || r.httpStatus() == 410) {
                    int failures = store.failures(program, type) + 1;
                    boolean broken = failures >= 2;
                    return store.record(program, type, url, broken ? "BROKEN" : "SOURCE_UNAVAILABLE", null, null,
                            "HTTP " + r.httpStatus() + (broken ? " on " + failures + " consecutive checks" : " once; rechecked next run"), now, failures);
                }
                return store.record(program, type, url, "SOURCE_UNAVAILABLE", null, null, r.error(), now, null);
            }
            default -> {
                return store.record(program, type, url, "SOURCE_UNAVAILABLE", null, null, r.kind() + ": " + r.error(), now, null);
            }
        }
    }

    /** The stored programme page must still pass the rule it was stored under (exactly one h1 naming the programme). */
    static boolean stillTheProgramme(String html, String finalUrl, String registeredUrl, PolonSources.Candidate candidate) {
        return PolishPageAdapter.extract(html, finalUrl, registeredUrl, candidate).stream()
                .anyMatch(f -> "official_program_url".equals(f.values().get("field")));
    }
}

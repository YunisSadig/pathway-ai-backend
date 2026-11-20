package com.pathwayai.backend.provider.polon;

import com.pathwayai.backend.refresh.extract.ContextualScopedExtractionAdapter;
import com.pathwayai.backend.refresh.extract.ExtractedFact;
import com.pathwayai.backend.refresh.extract.ExtractedFact.FactType;
import com.pathwayai.backend.refresh.registry.SourceMonitor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Reads a Polish university's own page for the reviewed POL-on programmes it concerns. A programme page states the official
 * programme link only if the page names the programme; a fees page states the fees link the same way, plus the one fee the page
 * states unambiguously for the programme (a reviewed strategy per university). A fee the page does not state, or states without
 * its audience or period, is simply not extracted: unknown stays unknown, and a year is stored only when the page prints it.
 */
@Component
public class PolishPageAdapter implements ContextualScopedExtractionAdapter {
    public static final String NAME = "pl-university-page";
    public static final String VERSION = "1";
    static final int MAX_VALUE = 400;

    private final PolonSources sources;
    private final JdbcClient jdbc;

    public PolishPageAdapter(PolonSources sources, JdbcClient jdbc) {
        this.sources = sources;
        this.jdbc = jdbc;
    }

    @Override public String name() { return NAME; }

    @Override public String version() { return VERSION; }

    @Override
    public Map<String, List<ExtractedFact>> extractHtml(String html, String finalUrl, SourceMonitor monitor) {
        String university = jdbc.sql("select slug from university where id=:u").param("u", monitor.universityId()).query(String.class).single();
        Map<String, List<ExtractedFact>> out = new LinkedHashMap<>();
        for (var c : sources.candidates()) {
            if (!c.identityConfirmed() || !c.university().equals(university)) continue;
            var facts = extract(html, finalUrl, monitor.url(), c);
            if (facts.isEmpty()) continue;
            jdbc.sql("select p.slug from degree_program p join program_external_id e on e.program_id=p.id where e.scheme=:s and e.value=:v")
                    .param("s", PolonParser.SCHEME_COURSE).param("v", c.courseUuid()).query(String.class).optional()
                    .ifPresent(slug -> out.put(slug, facts));
        }
        return out;
    }

    /** What this reviewed page states for this candidate; empty when it is not the reviewed page or does not name the programme. */
    public static List<ExtractedFact> extract(String html, String finalUrl, String registeredUrl, PolonSources.Candidate c) {
        boolean programPage = registeredUrl.equals(c.programUrl());
        boolean feePage = registeredUrl.equals(c.feePageUrl());
        if (!c.identityConfirmed() || c.pageMatch() == null || (!programPage && !feePage) || !PolonSources.owned(finalUrl, c.university()))
            return List.of();
        Document doc = Jsoup.parse(html, finalUrl);
        boolean ownPage = isProgrammePage(doc, c);
        doc.select("nav,header,footer,script,style").remove();
        if (!norm(doc.text()).toLowerCase(Locale.ROOT).contains(c.pageMatch().toLowerCase(Locale.ROOT))) return List.of();
        List<ExtractedFact> facts = new ArrayList<>();
        if (programPage && ownPage) facts.add(fact(c, "official_program_url", "", finalUrl, null)); // an offer list is not the programme's page
        if (feePage) {
            if (c.feesUrl() != null || c.feeStrategy() != null) facts.add(fact(c, "financial_url", "financial", finalUrl, null));
            if ("AGH_TABLE".equals(c.feeStrategy())) aghTuition(doc, c).ifPresent(facts::add);
            if ("PUT_TABLE".equals(c.feeStrategy())) putTuition(doc, c).ifPresent(facts::add);
            if ("UEP_LINES".equals(c.feeStrategy())) uepTuition(doc, c).ifPresent(facts::add);
        }
        return facts;
    }

    /**
     * A page is the programme's own page only if it has exactly one h1 and that heading contains the programme name. An offer or
     * landing page that merely lists the programme ("Education offer", "Educational offer") never qualifies, and no offer-link type exists.
     */
    static boolean isProgrammePage(Document doc, PolonSources.Candidate c) {
        var h1 = doc.select("h1");
        return c.pageMatch() != null && h1.size() == 1
                && norm(h1.first().text()).toLowerCase(Locale.ROOT).contains(c.pageMatch().toLowerCase(Locale.ROOT));
    }

    private static ExtractedFact fact(PolonSources.Candidate c, String field, String scope, String value, String academicYear) {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("field", field);
        v.put("scope", scope);
        v.put("value", value);
        v.put("educationOid", c.courseUuid()); // the POL-on course id plays the role of the education identifier
        if (academicYear != null) v.put("academicYear", academicYear);
        return ExtractedFact.of(FactType.UNIVERSITY_FACT, field + "|" + scope, v);
    }

    private static final Pattern AGH_HEAD = Pattern.compile("(?i)semestral tuition fees for full-time studies in the academic year (\\d{4}/\\d{4})");
    private static final Pattern AGH_CELL = Pattern.compile("(?i)^EUR\\s*(\\d{3,5})\\s*/\\s*semester$");

    /** AGH: a semester fee in EUR per field of study, in the full-time table whose heading prints the academic year. */
    static java.util.Optional<ExtractedFact> aghTuition(Document doc, PolonSources.Candidate c) {
        if (!norm(doc.text()).toLowerCase(Locale.ROOT).contains("international students")) return java.util.Optional.empty();
        String year = null;
        Element table = null;
        for (Element e : doc.getAllElements()) {
            if (e.tagName().equals("table")) {
                if (year != null) { table = e; break; }
                continue;
            }
            var m = AGH_HEAD.matcher(norm(headingText(e)));
            if (m.find()) year = m.group(1);
        }
        if (table == null) return java.util.Optional.empty();
        String levelHeader = c.entryLevel().equals("BACHELOR") ? "bachelor studies" : "master studies";
        int column = -1;
        String amount = null;
        int matches = 0;
        for (Element row : table.select("tr")) {
            var cells = row.select("th,td");
            for (int i = 0; i < cells.size(); i++) if (norm(cells.get(i).text()).equalsIgnoreCase(levelHeader)) column = i;
            if (cells.size() >= 3 && column >= 0 && cells.size() > column && norm(cells.get(0).text()).equalsIgnoreCase(c.feeRow())) {
                matches++;
                var m = AGH_CELL.matcher(norm(cells.get(column).text()));
                amount = m.matches() ? m.group(1) : null;
            }
        }
        if (matches != 1 || amount == null) return java.util.Optional.empty();
        Map<String, String> v = new LinkedHashMap<>();
        v.put("studentCategory", "INTERNATIONAL");
        v.put("amount", amount);
        v.put("currency", "EUR");
        v.put("billingPeriod", "PER_SEMESTER");
        v.put("academicYear", year);
        v.put("scopeNote", "Semester fee for full-time studies for international students, as printed on the university's fees page for the academic year " + year + ".");
        return java.util.Optional.of(ExtractedFact.of(FactType.TUITION, year + "|INTERNATIONAL", v));
    }

    private static final Pattern PUT_HEAD = Pattern.compile("(?i)tuition fees? for international students in the (\\d{4}/\\d{4}) academic year");

    /** Poznan UT: the row of the fee table lists programmes; the amount cell is kept word for word (it can state two parts). */
    static java.util.Optional<ExtractedFact> putTuition(Document doc, PolonSources.Candidate c) {
        String year = null;
        Element table = null;
        for (Element e : doc.getAllElements()) {
            if (e.tagName().equals("table")) {
                if (year != null) { table = e; break; }
                continue;
            }
            var m = PUT_HEAD.matcher(norm(headingText(e)));
            if (m.find()) year = m.group(1);
        }
        if (table == null) return java.util.Optional.empty();
        String amount = null;
        int matches = 0;
        for (Element row : table.select("tr")) {
            var cells = row.select("th,td");
            if (cells.size() < 2) continue;
            for (String name : norm(cells.get(0).text()).split(","))
                if (name.strip().equalsIgnoreCase(c.feeRow())) { matches++; amount = norm(cells.get(1).text()); }
        }
        if (matches != 1 || amount == null || amount.isBlank() || amount.length() > MAX_VALUE) return java.util.Optional.empty();
        return java.util.Optional.of(fact(c, "tuition_scope", "financial", amount, year));
    }

    private static final Pattern UEP = Pattern.compile("(?i)current master['’]s degree fees are:?\\s*(\\d[\\d ]*\\d PLN per semester)\\s*(\\d[\\d ]*\\d PLN per year)?");

    /** Poznan UEB: the page prints "current" fees without naming an academic year, so none is stored. */
    static java.util.Optional<ExtractedFact> uepTuition(Document doc, PolonSources.Candidate c) {
        var m = UEP.matcher(norm(doc.text()));
        if (!m.find()) return java.util.Optional.empty();
        String value = m.group(2) == null ? m.group(1) : m.group(1) + "; " + m.group(2);
        return java.util.Optional.of(fact(c, "tuition_scope", "financial", value + " (the page calls these the current fees)", null));
    }

    private static String headingText(Element e) {
        return e.tagName().matches("h[1-6]|p|strong|b|caption|th|span|li|td") ? e.text() : e.ownText();
    }

    static String norm(String s) {
        return s.replace(' ', ' ').replaceAll("\\s+", " ").strip();
    }
}

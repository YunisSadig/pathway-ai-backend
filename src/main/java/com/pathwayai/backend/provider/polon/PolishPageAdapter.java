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
        String fullText = norm(doc.text()); // before navigation is removed: the registration year lives in the page header
        doc.select("nav,header,footer,script,style").remove();
        if (!norm(doc.text()).toLowerCase(Locale.ROOT).contains(c.pageMatch().toLowerCase(Locale.ROOT))) return List.of();
        List<ExtractedFact> facts = new ArrayList<>();
        if (programPage && ownPage) facts.add(fact(c, "official_program_url", "", finalUrl, null)); // an offer list is not the programme's page
        if (programPage && c.factStrategy() != null) {
            switch (c.factStrategy()) {
                case "AGH_ACCORDION" -> facts.addAll(aghProgramme(doc, c));
                case "UW_IRK" -> facts.addAll(uwIrk(doc, c, fullText));
                case "SGH_DL" -> { if (ownPage) facts.addAll(sghProgramme(doc, c)); }
                default -> { }
            }
        }
        if (feePage) {
            if ("TUL_TABLE".equals(c.feeStrategy())) tulTuition(doc, c).ifPresent(facts::add);
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
        // the university-fact table stores an academic year as 2026-2027 (a fee row keeps 2026/2027)
        return java.util.Optional.of(fact(c, "tuition_scope", "financial", amount, year == null ? null : year.replace('/', '-')));
    }

    private static final Pattern UEP = Pattern.compile("(?i)current master['’]s degree fees are:?\\s*(\\d[\\d ]*\\d PLN per semester)\\s*(\\d[\\d ]*\\d PLN per year)?");

    /** Poznan UEB: the page prints "current" fees without naming an academic year, so none is stored. */
    static java.util.Optional<ExtractedFact> uepTuition(Document doc, PolonSources.Candidate c) {
        var m = UEP.matcher(norm(doc.text()));
        if (!m.find()) return java.util.Optional.empty();
        String value = m.group(2) == null ? m.group(1) : m.group(1) + "; " + m.group(2);
        return java.util.Optional.of(fact(c, "tuition_scope", "financial", value + " (the page calls these the current fees)", null));
    }

    private static final Pattern TUL_YEAR = Pattern.compile("(?i)tuition fees per semester for full-time and part-time studies for foreign candidates in the academic year (\\d{4}/\\d{4})");
    private static final Pattern PLN = Pattern.compile("^(\\d{3,6}) PLN$");

    /**
     * Lodz UT: one table of English and Polish programmes. A row is the programme only if it names it exactly, shows the English-language
     * flag, is full-time, and lies in the block of the programme's cycle (the cycle cell spans several rows). The amount is per semester
     * and the academic year is the one the page's own sentence prints.
     */
    static java.util.Optional<ExtractedFact> tulTuition(Document doc, PolonSources.Candidate c) {
        var yearMatch = TUL_YEAR.matcher(norm(doc.text()));
        if (!yearMatch.find()) return java.util.Optional.empty();
        String year = yearMatch.group(1);
        String wantCycle = c.entryLevel().equals("BACHELOR") ? "first-cycle" : "second-cycle";
        String cycle = null;
        String amount = null;
        int matches = 0;
        for (Element row : doc.select("tr")) {
            boolean english = !row.select("img[alt*=Wielkiej Brytanii]").isEmpty();
            boolean fullTime = false, named = false;
            String pln = null;
            for (Element cell : row.select("td")) {
                String t = norm(cell.text());
                if (t.matches("(?i)(first|second)-cycle.*")) cycle = t.toLowerCase(Locale.ROOT).startsWith("first") ? "first-cycle" : "second-cycle";
                if (t.equals("FT")) fullTime = true;
                if (PLN.matcher(t).matches()) pln = t;
                if (t.equalsIgnoreCase(c.feeRow())) named = true;
            }
            if (named && english && fullTime && pln != null && wantCycle.equals(cycle)) { matches++; amount = PLN.matcher(pln).replaceFirst("$1"); }
        }
        if (matches != 1) return java.util.Optional.empty();
        Map<String, String> v = new LinkedHashMap<>();
        v.put("studentCategory", "INTERNATIONAL");
        v.put("amount", amount);
        v.put("currency", "PLN");
        v.put("billingPeriod", "PER_SEMESTER");
        v.put("academicYear", year);
        v.put("scopeNote", "Semester fee for full-time studies in English charged to persons who are not Polish citizens and are not covered by Article 324(2) of the Law on Higher Education and Science, as printed on the university's fees page for the academic year " + year + ".");
        return java.util.Optional.of(ExtractedFact.of(FactType.TUITION, year + "|INTERNATIONAL", v));
    }

    /** AGH: the offer list has one accordion item per programme, headed by its exact name, holding a one-row table. No year is printed, so none is stored. */
    static List<ExtractedFact> aghProgramme(Document doc, PolonSources.Candidate c) {
        Element button = null;
        int n = 0;
        for (Element b : doc.select("button.accordion-button")) if (norm(b.text()).equalsIgnoreCase(c.pageMatch())) { button = b; n++; }
        if (n != 1) return List.of();
        Element item = button.closest(".accordion-item");
        Element table = item == null ? null : item.selectFirst("table");
        if (table == null || table.select("tr").size() < 2) return List.of();
        var head = table.select("tr").get(0).select("th,td");
        var body = table.select("tr").get(1).select("th,td");
        if (head.size() != body.size()) return List.of();
        String recruitment = null, start = null, duration = null;
        for (int i = 0; i < head.size(); i++) {
            String h = norm(head.get(i).text()).toLowerCase(Locale.ROOT), val = norm(body.get(i).text());
            if (h.startsWith("recr")) recruitment = val;
            else if (h.startsWith("start")) start = val;
            else if (h.startsWith("duration")) duration = val;
        }
        List<ExtractedFact> out = new ArrayList<>();
        if (duration != null && !duration.isBlank()) out.add(fact(c, "duration_structure", "", duration, null));
        if (recruitment != null && !recruitment.isBlank())
            out.add(fact(c, "application_period", "year-not-stated", "Recruitment: " + recruitment
                    + (start == null || start.isBlank() ? "" : "; start of studies: " + start) + " (the page prints no year)", null));
        return out;
    }

    /** SGH: the programme page is a definition list; each fact is the page's own sentence. */
    static List<ExtractedFact> sghProgramme(Document doc, PolonSources.Candidate c) {
        List<ExtractedFact> out = new ArrayList<>();
        for (Element dt : doc.select("dt")) {
            Element dd = dt.nextElementSibling();
            if (dd == null || !dd.tagName().equals("dd")) continue;
            String label = norm(dt.text());
            if (label.equalsIgnoreCase("Mode and duration of study")) {
                for (Element p : dd.select("p")) {
                    String t = norm(p.text());
                    if (t.toLowerCase(Locale.ROOT).startsWith("mode of study:")) {
                        String mode = t.substring("mode of study:".length()).strip();
                        if (!mode.isEmpty()) out.add(fact(c, "study_mode", "", Character.toUpperCase(mode.charAt(0)) + mode.substring(1), null));
                    } else if (t.toLowerCase(Locale.ROOT).startsWith("duration:")) {
                        String d = t.substring("duration:".length()).strip();
                        if (!d.isEmpty()) out.add(fact(c, "duration_structure", "", d, null));
                    }
                }
            }
            if (label.equalsIgnoreCase("Admissions and tuition")) {
                var ps = dd.select("p");
                for (int i = 0; i < ps.size() - 1; i++)
                    if (ps.get(i).hasClass("header-two") && norm(ps.get(i).text()).equalsIgnoreCase("Tuition")) {
                        String t = norm(ps.get(i + 1).text());
                        if (!t.isBlank() && t.length() <= MAX_VALUE)
                            out.add(fact(c, "tuition_scope", "financial", t + " (the page prints no academic year or applicant category)", null));
                    }
            }
        }
        return out;
    }

    /**
     * University of Warsaw IRK programme page for one registration: degree title, place, admission procedure, entrance exam, English
     * requirement and dated deadlines, each copied word for word from its own heading's section. The academic year is the one the page's
     * header prints ("... - 2026/2027"); a missing section is simply not extracted.
     */
    static List<ExtractedFact> uwIrk(Document doc, PolonSources.Candidate c, String text) {
        List<ExtractedFact> out = new ArrayList<>();
        var year = Pattern.compile("Degree programmes \\(first-cycle, second-cycle, long-cycle\\) - (\\d{4})/(\\d{4})").matcher(text);
        String academicYear = year.find() ? year.group(1) + "-" + year.group(2) : null;
        String title = norm(doc.title());
        var mode = Pattern.compile("(?i)full-time|part-time").matcher(title);
        if (mode.find() && title.toLowerCase(Locale.ROOT).startsWith(c.pageMatch().toLowerCase(Locale.ROOT)))
            out.add(fact(c, "study_mode", "", Character.toUpperCase(mode.group().charAt(0)) + mode.group().substring(1).toLowerCase(Locale.ROOT), null));
        section(text, "Professional degree you will be awarded after completing studies\\s*:\\s*(.+?)\\s+Where and when your classes", 200)
                .ifPresent(v -> out.add(fact(c, "qualification", "", v, null)));
        section(text, "Place:\\s*(.+?)\\s+Time:", 300).ifPresent(v -> out.add(fact(c, "campus", "", v, null)));
        section(text, "(Admission to the (?:first|second)-cycle studies programme may be granted .+?\\.)\\s+The qualification is based", 400)
                .ifPresent(v -> out.add(fact(c, "eligibility", "", v, academicYear)));
        section(text, "(The qualification is based on .+?)\\s+A detailed list of topics", 900)
                .ifPresent(v -> out.add(fact(c, "entrance_tests", "", v, academicYear)));
        section(text, "(Minimum percentage of points needed to be qualified: \\d+%)", 200)
                .ifPresent(v -> out.add(fact(c, "selection", "", v, academicYear)));
        section(text, "Checking the candidates' competence to undertake studies conducted in the English language\\s+(.+?)\\s+Deadlines", 1500)
                .ifPresent(v -> out.add(fact(c, "language_tests", "", v, academicYear)));
        section(text, "Deadlines\\s+(Date of entrance exam:.+?)\\s+Payments", 900)
                .ifPresent(v -> out.add(fact(c, "application_period", academicYear == null ? "" : academicYear, v, academicYear)));
        return out;
    }

    private static java.util.Optional<String> section(String text, String regex, int max) {
        var m = Pattern.compile(regex).matcher(text);
        if (!m.find()) return java.util.Optional.empty();
        String v = m.group(1).strip();
        return v.isEmpty() || v.length() > max ? java.util.Optional.empty() : java.util.Optional.of(v);
    }

    private static String headingText(Element e) {
        return e.tagName().matches("h[1-6]|p|strong|b|caption|th|span|li|td") ? e.text() : e.ownText();
    }

    static String norm(String s) {
        return s.replace(' ', ' ').replaceAll("\\s+", " ").strip();
    }
}

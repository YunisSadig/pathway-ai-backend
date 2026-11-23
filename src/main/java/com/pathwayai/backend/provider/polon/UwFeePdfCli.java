package com.pathwayai.backend.provider.polon;

import com.pathwayai.backend.refresh.extract.ExtractedFact;
import com.pathwayai.backend.refresh.extract.ExtractedFact.FactType;
import com.pathwayai.backend.refresh.extract.PageCanonicalizer;
import com.pathwayai.backend.refresh.fetch.PageFetcher;
import com.pathwayai.backend.refresh.pipeline.FactNormalizer;
import com.pathwayai.backend.refresh.pipeline.FactValidator;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Operator command for the University of Warsaw fee PDF: {@code --app.poland.command=uw-fees-pdf-preview|uw-fees-pdf-stage}.
 * <p>
 * A fee read from a PDF is NEVER published by this command. {@code stage} writes only {@code staged_change} rows with status
 * {@code PENDING_REVIEW}; a person approves or rejects each one with {@code --app.refresh.command=review-approve|review-reject}.
 * There is no monitor for the PDF (nothing scheduled reads it) and no code path here calls {@code ProgramFacts.apply}.
 * Each row's page number, exact row text and the PDF's SHA-256 travel with the item. A row whose applicant category, cycle or
 * year cannot be attributed is not staged at all; the preview prints why ("Not stated").
 */
@Component
@ConditionalOnNotWebApplication
@ConditionalOnExpression("'${app.poland.command:}'.startsWith('uw-fees-pdf')")
public class UwFeePdfCli implements ApplicationRunner {
    static final String PDF_URL = "https://rekrutacja.uw.edu.pl/files/pdf/tuition_fees_2026-2027_05.10.2026.pdf";
    static final String PARSER = "uw-fees-pdf@1+pdfbox3.0.8";
    private static final String PUBLISHER = "University of Warsaw (Rekrutacja UW)";

    /** Candidate key -> the programme name as printed in the PDF and its cycle (1 = bachelor-level, 2 = master-level). */
    private static final Map<String, Object[]> MAP = Map.of(
            "uniwersytet-machine-learning-master", new Object[] {"MACHINE LEARNING", 2},
            "uniwersytet-data-science-and-business-analytics-master", new Object[] {"DATA SCIENCE AND BUSINESS ANALYTICS", 2},
            "uniwersytet-finance-international-investment-and-accounting-bachelor",
            new Object[] {"FINANCE, INTERNATIONAL INVESTMENT AND ACCOUNTING", 1});

    private record Item(String key, long programId, UwFeePdf.Row row, ExtractedFact fact, String reason) { }

    private final PolonSources sources;
    private final PageFetcher fetcher;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final String command, expected;

    public UwFeePdfCli(PolonSources sources, PageFetcher fetcher, JdbcClient jdbc, Clock clock,
                       @Value("${app.poland.command}") String command,
                       @Value("${app.poland.expected-fingerprint:}") String expected) {
        this.sources = sources; this.fetcher = fetcher; this.jdbc = jdbc; this.clock = clock;
        this.command = command; this.expected = expected;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!List.of("uw-fees-pdf-preview", "uw-fees-pdf-stage").contains(command))
            throw new IllegalArgumentException("command must be uw-fees-pdf-preview or uw-fees-pdf-stage");
        var r = fetcher.fetchPdf(PDF_URL);
        if (r.kind() != PageFetcher.Kind.OK) throw new IllegalStateException("fee PDF not readable: " + r.kind() + " " + r.error());
        byte[] pdf = r.body().getBytes(StandardCharsets.ISO_8859_1);
        String sha = PageCanonicalizer.sha256(new String(pdf, StandardCharsets.ISO_8859_1));
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        var byName = new LinkedHashMap<String, Integer>();
        var keyByName = new LinkedHashMap<String, String>();
        for (var c : sources.candidates()) {
            if (!"uniwersytet-warszawski".equals(c.university()) || !c.identityConfirmed()) continue;
            Object[] m = MAP.get(c.key());
            if (m == null) { System.out.println("UWPDF_NOT_STAGED " + c.key() + " reason=no PDF programme name is mapped for it"); continue; }
            byName.put((String) m[0], (Integer) m[1]);
            keyByName.put((String) m[0], c.key());
        }
        List<Item> items = new ArrayList<>();
        for (var row : UwFeePdf.read(pdf, byName)) {
            String key = keyByName.get(row.name());
            if (row.fee() == null) {
                System.out.println("UWPDF_NOT_STAGED " + key + " page=" + row.page() + " reason=" + row.notStated() + " row=\"" + row.text() + "\"");
                continue;
            }
            Long programId = jdbc.sql("""
                    select e.program_id from program_external_id e join degree_program p on p.id = e.program_id
                    where e.scheme = :s and e.value = :v""").param("s", PolonParser.SCHEME_COURSE)
                    .param("v", sources.candidates().stream().filter(c -> c.key().equals(key)).findFirst().orElseThrow().courseUuid())
                    .query(Long.class).optional().orElse(null);
            if (programId == null) { System.out.println("UWPDF_NOT_STAGED " + key + " reason=programme is not imported in this database"); continue; }
            var f = row.fee();
            Map<String, String> v = new LinkedHashMap<>();
            v.put("studentCategory", "INTERNATIONAL");
            v.put("amount", f.amount());
            v.put("currency", f.currency());
            v.put("billingPeriod", "PER_YEAR");
            v.put("academicYear", f.academicYear());
            v.put("scopeNote", "Non-EU/EFTA citizens, " + f.printedAmount() + " per year, as printed in the University of Warsaw fee PDF for "
                    + f.academicYear() + " (page " + row.page() + ").");
            var fact = FactNormalizer.normalize(ExtractedFact.of(FactType.TUITION, f.academicYear() + "|INTERNATIONAL", v));
            var errors = FactValidator.validate(fact, true, today);
            if (!errors.isEmpty()) { System.out.println("UWPDF_NOT_STAGED " + key + " reason=invalid: " + errors); continue; }
            String reason = ("PDF page " + row.page() + ", exact row text: \"" + row.text() + "\" (pdf sha256 " + sha.substring(0, 12)
                    + "). Position-based reading: compare with the PDF before approving.");
            items.add(new Item(key, programId, row, fact, reason.length() > 500 ? reason.substring(0, 497) + "..." : reason));
        }
        for (var i : items)
            System.out.println("UWPDF_ITEM " + i.key() + " page=" + i.row().page() + " tuition=" + i.fact().canonicalValue() + " row=\"" + i.row().text() + "\"");
        List<String> lines = new ArrayList<>();
        items.forEach(i -> lines.add(i.programId() + "|" + i.fact().canonicalValue()));
        lines.sort(String::compareTo);
        String fingerprint = PageCanonicalizer.sha256(sha + "\n" + String.join("\n", lines));
        System.out.println("UWPDF_PLAN pdf=" + PDF_URL + " sha256=" + sha + " items=" + items.size() + " fingerprint=" + fingerprint);
        if (command.equals("uw-fees-pdf-preview")) { System.out.println("UWPDF_PREVIEW no writes"); return; }
        if (!fingerprint.equals(expected)) throw new IllegalArgumentException("Plan differs from the approved fingerprint; run and review a fresh preview");
        stage(items, today);
    }

    private void stage(List<Item> items, LocalDate today) {
        var now = clock.instant();
        long source = jdbc.sql("""
                insert into source(url,title,publisher,source_type,retrieved_on) values(:u,:t,:p,'UNIVERSITY_WEBSITE',:d)
                on conflict(url) do update set retrieved_on=excluded.retrieved_on returning id""")
                .param("u", PDF_URL).param("t", "University of Warsaw tuition fees 2026/2027 (PDF)").param("p", PUBLISHER)
                .param("d", today).query(Long.class).single();
        long university = jdbc.sql("select id from university where slug='uniwersytet-warszawski'").query(Long.class).single();
        int staged = 0, already = 0;
        for (var i : items) {
            String dedupe = PageCanonicalizer.sha256(source + "|" + FactType.TUITION + "|" + i.fact().key() + "|" + i.fact().canonicalValue() + "|uw-pdf|program=" + i.programId());
            // status is PENDING_REVIEW by construction: this class has no way to publish a fact
            Long id = jdbc.sql("""
                    insert into staged_change (source_id, university_id, program_id, fact_type, fact_key, old_value, new_value,
                        risk, status, reason, parser_version, dedupe_key, detected_at, decided_at, decided_by)
                    values (:s, :u, :p, 'TUITION', :k, null, :new, 'HIGH', 'PENDING_REVIEW', :reason, :pv, :dk, :now, null, null)
                    on conflict (dedupe_key) do nothing returning id""")
                    .param("s", source).param("u", university).param("p", i.programId()).param("k", i.fact().key())
                    .param("new", i.fact().canonicalValue()).param("reason", i.reason()).param("pv", PARSER).param("dk", dedupe)
                    .param("now", Timestamp.from(now)).query(Long.class).optional().orElse(null);
            if (id == null) already++; else { staged++; System.out.println("UWPDF_STAGED #" + id + " " + i.key()); }
        }
        System.out.println("UWPDF_DONE staged=" + staged + " alreadyStaged=" + already + " published=0");
    }
}

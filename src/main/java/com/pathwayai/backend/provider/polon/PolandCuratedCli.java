package com.pathwayai.backend.provider.polon;

import com.pathwayai.backend.provider.studyinfo.FieldTaxonomy;
import com.pathwayai.backend.refresh.RefreshProperties;
import com.pathwayai.backend.refresh.extract.ExtractedFact;
import com.pathwayai.backend.refresh.extract.PageCanonicalizer;
import com.pathwayai.backend.refresh.fetch.ApprovedHosts;
import com.pathwayai.backend.refresh.fetch.PageFetcher;
import com.pathwayai.backend.refresh.pipeline.ChangePipeline;
import com.pathwayai.backend.refresh.pipeline.ProgramFacts;
import com.pathwayai.backend.refresh.registry.SourceMonitor;
import com.pathwayai.backend.refresh.registry.SourceMonitorRepository;
import com.pathwayai.backend.refresh.schedule.CheckSchedule;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Explicit operator operation for Poland: {@code --app.poland.command=preview|apply}. Preview is read-only and prints, per
 * programme, which facts are verified (with the source URL and the date read) and which are not stated. Apply requires the
 * fingerprint of an approved preview. Nothing is public: the catalogue gate and the release approval stay as they are.
 * Tuition, requirements and deadlines are not in POL-on and no university extractor exists yet: they are NOT imported.
 */
@Component
@ConditionalOnNotWebApplication
@ConditionalOnProperty(name = "app.poland.command")
public class PolandCuratedCli implements ApplicationRunner {
    static final String PUBLISHER = "Ministry of Science and Higher Education (POL-on / RAD-on open data)";
    static final String NOT_STATED = "Not stated (no official extractor for it yet)";

    record Verified(PolonSources.Candidate candidate, PolonParser.Program program, PageFetcher.Result record,
                    String pageStatus, String feesStatus, Long existingId, List<ExtractedFact> pageFacts,
                    java.util.Map<ExtractedFact, String> factSource) { }
    record Skipped(PolonSources.Candidate candidate, String reason) { }

    private final PolonSources sources;
    private final PageFetcher fetcher;
    private final FieldTaxonomy taxonomy;
    private final JdbcClient jdbc;
    private final SourceMonitorRepository monitors;
    private final ChangePipeline pipeline;
    private final ProgramFacts programFacts;
    private final ApprovedHosts approvedHosts;
    private final RefreshProperties props;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final String command, expected;

    public PolandCuratedCli(PolonSources sources, PageFetcher fetcher, FieldTaxonomy taxonomy, JdbcClient jdbc,
                            SourceMonitorRepository monitors, ChangePipeline pipeline, ProgramFacts programFacts,
                            ApprovedHosts approvedHosts, RefreshProperties props, Clock clock, PlatformTransactionManager tx,
                            @Value("${app.poland.command}") String command,
                            @Value("${app.poland.expected-fingerprint:}") String expected) {
        this.sources = sources; this.fetcher = fetcher; this.taxonomy = taxonomy; this.jdbc = jdbc; this.monitors = monitors;
        this.pipeline = pipeline; this.programFacts = programFacts; this.approvedHosts = approvedHosts; this.props = props;
        this.clock = clock; this.transaction = new TransactionTemplate(tx); this.command = command; this.expected = expected;
    }

    record Plan(List<Verified> verified, List<Skipped> skipped, String fingerprint, java.util.Map<String, String> slugs,
                java.util.Map<String, PageFetcher.Result> pages) { }

    @Override
    public void run(ApplicationArguments args) {
        if (command.startsWith("uw-fees-pdf")) return; // handled by UwFeePdfCli
        if (!List.of("preview", "apply").contains(command))
            throw new IllegalArgumentException("Poland command must be preview or apply");
        var plan = plan();
        List<Verified> verified = plan.verified();
        List<Skipped> skipped = plan.skipped();
        String today = LocalDate.now(clock).toString();
        for (var v : verified) printVerified(v, today);
        for (var s : skipped) System.out.println("POLAND_SKIPPED " + s.candidate().key() + " reason=" + s.reason());
        String fingerprint = plan.fingerprint();
        plan.verified().forEach(v -> System.out.println("POLAND_SLUG " + v.candidate().key() + " planned=" + plan.slugs().get(v.candidate().key()) + " current=" + (v.existingId() == null ? "(new)" : jdbc.sql("select slug from degree_program where id=:i").param("i", v.existingId()).query(String.class).single())));
        System.out.println("POLAND_PLAN release=" + PolonSources.RELEASE + " candidates=" + sources.candidates().size() + " importable=" + verified.size()
                + " skipped=" + skipped.size() + " newPrograms=" + verified.stream().filter(v -> v.existingId() == null).count()
                + " fingerprint=" + fingerprint);
        if (command.equals("preview")) { System.out.println("POLAND_PREVIEW no writes, no activation, cataloguePublic unchanged"); return; }
        if (!fingerprint.equals(expected)) throw new IllegalArgumentException("Plan differs from the approved fingerprint; run and review a fresh preview");
        transaction.executeWithoutResult(status -> apply(verified, plan.slugs(), plan.pages()));
        System.out.println("POLAND_APPLIED " + PolonSources.RELEASE + " " + verified.size() + " programmes; nothing was deleted");
    }

    /** Read-only: reads POL-on and the universities' pages, writes nothing. */
    Plan plan() {
        List<Verified> verified = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        var pages = new java.util.LinkedHashMap<String, PageFetcher.Result>(); // pages read OK in this pass, shared between programmes
        for (var c : sources.candidates()) {
            try {
                if (!c.identityConfirmed()) { skipped.add(new Skipped(c, "identity mapping unconfirmed (POL-on name \"" + c.polonName() + "\" differs from the university page): a person must confirm it")); continue; }
                var record = fetch(PolonSources.recordUrl(c.courseUuid()));
                var program = PolonParser.parse(record.body(), c, taxonomy);
                boolean supported = c.pageMatch() != null; // no pageMatch = the university's pages are not readable reliably: not read, no link, no fact
                String page = c.programUrl() == null ? "NOT STATED (no live official programme page found)"
                        : !supported ? "NOT STATED (university pages unsupported: connection resets on repeated reads)" : check(c.programUrl(), c, pages);
                String fees = c.feesUrl() == null ? "none listed" : !supported ? "NOT STATED (unsupported)" : check(c.feesUrl(), c, pages);
                if (supported) { // admissions and requirement pages: read now so the plan (and its fingerprint) includes what they state
                    if (c.admissionsUrl() != null) check(c.admissionsUrl(), c, pages);
                    for (var x : c.extras()) check(x.url(), c, pages);
                }
                Long existing = jdbc.sql("select program_id from program_external_id where scheme=:s and value=:v")
                        .param("s", PolonParser.SCHEME_COURSE).param("v", c.courseUuid()).query(Long.class).optional().orElse(null);
                List<ExtractedFact> pageFacts = new ArrayList<>();
                var factSource = new java.util.IdentityHashMap<ExtractedFact, String>();
                for (String u : pageUrls(c)) {
                    var r = pages.get(u);
                    if (r == null) continue;
                    for (var f : PolishPageAdapter.extract(r.body(), r.finalUrl(), u, c)) { pageFacts.add(f); factSource.put(f, u); }
                }
                verified.add(new Verified(c, program, record, page, fees, existing, pageFacts, factSource));
            } catch (RuntimeException e) {
                skipped.add(new Skipped(c, "not verified: " + e.getMessage()));
            }
        }
        var slugs = slugs(verified);
        return new Plan(verified, skipped, fingerprint(verified, slugs), slugs, pages);
    }

    /** The distinct reviewed pages of a candidate: its programme page and the page that states its fees. */
    static List<String> pageUrls(PolonSources.Candidate c) {
        var urls = new java.util.LinkedHashSet<String>();
        if (c.programUrl() != null) urls.add(c.programUrl());
        if (c.feePageUrl() != null) urls.add(c.feePageUrl());
        if (c.admissionsUrl() != null) urls.add(c.admissionsUrl());
        for (var e : c.extras()) urls.add(e.url());
        return List.copyOf(urls);
    }

    /**
     * The programme slug per candidate key. A name shared by two programmes of one university (same slug) gets the degree level
     * as suffix on EACH of them ("artificial-intelligence-bachelor"), never a number; a unique name keeps the plain slug.
     */
    static java.util.Map<String, String> slugs(List<Verified> verified) {
        var byBase = new java.util.HashMap<String, Integer>();
        for (var v : verified) byBase.merge(v.candidate().university() + "|" + slugify(v.program().name()), 1, Integer::sum);
        var out = new java.util.LinkedHashMap<String, String>();
        for (var v : verified) {
            String base = slugify(v.program().name());
            out.put(v.candidate().key(), byBase.get(v.candidate().university() + "|" + base) > 1 ? base + "-" + v.program().degreeLevel().toLowerCase(Locale.ROOT) : base);
        }
        return out;
    }

    private void printVerified(Verified v, String today) {
        var p = v.program();
        String url = PolonSources.recordUrl(p.courseUuid());
        String period = "period not stated by POL-on (instance start " + p.startDate() + ")";
        System.out.println("POLAND_PROGRAMME " + v.candidate().key() + " existing=" + v.existingId());
        System.out.println("  VERIFIED name=\"" + p.name() + "\" level=" + p.degreeLevel() + " university=" + p.institution() + " src=" + url + " read=" + today);
        System.out.println("  VERIFIED language=en duration=" + p.semesters() + " semesters (" + p.semesters() * 6 + " months) ects=" + p.ects() + " form=" + p.form() + " " + period);
        System.out.println("  VERIFIED field=" + (p.fieldSlug() == null ? "NOT STATED (ISCED " + p.iscedCode() + " not in the reviewed field map)" : p.fieldSlug() + " (ISCED " + p.iscedCode() + " " + p.iscedName() + ")"));
        System.out.println("  PAGE programme=" + v.candidate().programUrl() + " -> " + v.pageStatus() + " | fees=" + v.candidate().feesUrl() + " -> " + v.feesStatus());
        boolean tuition = false;
        for (var f : v.pageFacts()) {
            var vals = f.values();
            String field = f.type() == ExtractedFact.FactType.TUITION ? "tuition" : vals.get("field");
            tuition |= f.type() == ExtractedFact.FactType.TUITION || "tuition_scope".equals(field);
            String src = v.factSource().get(f);
            String year = vals.get("academicYear") == null ? "UNKNOWN_YEAR (the page prints no academic year)" : vals.get("academicYear");
            String shown = f.type() == ExtractedFact.FactType.TUITION ? vals.get("currency") + " " + vals.get("amount") + " " + vals.get("billingPeriod") + " " + vals.get("studentCategory") : vals.get("value");
            System.out.println("  VERIFIED page-fact " + field + "=" + shown + " src=" + src + " read=" + today + " year=" + year);
        }
        java.util.function.Predicate<java.util.Set<String>> has = names -> v.pageFacts().stream().anyMatch(f -> f.type() == ExtractedFact.FactType.UNIVERSITY_FACT && names.contains(f.values().get("field")));
        System.out.println("  MISSING tuition=" + (tuition ? "stated (see above)" : NOT_STATED) + "; deadlines=" + (has.test(java.util.Set.of("application_period")) ? "stated (see above)" : NOT_STATED)
                + "; admission requirements=" + (has.test(java.util.Set.of("eligibility", "entrance_tests", "selection", "language_tests")) ? "stated (see above)" : NOT_STATED)
                + "; degree title/campus/mode/duration=" + (has.test(java.util.Set.of("qualification", "campus", "study_mode", "duration_structure")) ? "see above" : NOT_STATED) + "; application URL=" + NOT_STATED
                + (v.pageFacts().stream().anyMatch(f -> "official_program_url".equals(f.values().get("field"))) ? "" : "; official programme link=" + NOT_STATED));
    }

    /** The page must load over https from the university's own registered domain; its content is not interpreted. */
    private String check(String url, PolonSources.Candidate c, java.util.Map<String, PageFetcher.Result> pages) {
        URI uri = URI.create(url);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equals(uri.getScheme()) || !(host.equals(c.officialDomain()) || host.endsWith("." + c.officialDomain())))
            throw new IllegalStateException("not an official https page of " + c.officialDomain() + ": " + url);
        var r = pages.containsKey(url) ? pages.get(url) : fetchWithRetry(url);
        // an unreadable page never blocks the programme (POL-on states it); the page is simply not recorded as verified
        if (r.kind() == PageFetcher.Kind.OK) pages.put(url, r);
        return r.kind() == PageFetcher.Kind.OK ? "OK " + r.httpStatus() : "NOT VERIFIED (" + r.kind() + ")";
    }

    /** Pause between attempts of a failed page read (tests set it to 0). */
    static volatile long retryPauseMs = 15_000;

    /** A connection that fails is tried again (two more times, 15 s apart) so one flaky university server cannot change the plan. */
    private PageFetcher.Result fetchWithRetry(String url) {
        var r = fetcher.fetch(url, null, null);
        for (int attempt = 1; attempt < 3 && r.kind() == PageFetcher.Kind.FAILED; attempt++) {
            try {
                Thread.sleep(retryPauseMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            r = fetcher.fetch(url, null, null);
        }
        return r;
    }

    private PageFetcher.Result fetch(String url) {
        var r = fetcher.fetch(url, null, null);
        if (r.kind() != PageFetcher.Kind.OK) throw new IllegalStateException("POL-on record not readable: " + r.kind() + " " + r.error());
        return r;
    }

    private String fingerprint(List<Verified> verified, java.util.Map<String, String> slugs) {
        List<String> rows = new ArrayList<>();
        for (var v : verified) {
            rows.add(v.candidate().courseUuid() + "|" + v.candidate().courseInstanceUuid() + "|" + v.program().name() + "|" + v.program().degreeLevel() + "|" + slugs.get(v.candidate().key()));
            PolonParser.toFacts(v.program()).forEach(f -> rows.add(f.type() + f.key() + f.canonicalValue()));
            v.pageFacts().forEach(f -> rows.add(v.candidate().courseUuid() + "|page|" + f.type() + f.key() + f.canonicalValue()));
        }
        rows.sort(String::compareTo);
        return PageCanonicalizer.sha256(String.join("\n", rows));
    }

    private void apply(List<Verified> verified, java.util.Map<String, String> slugs, java.util.Map<String, PageFetcher.Result> pages) {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        jdbc.sql("select pg_advisory_xact_lock(721681204)").query((rs, n) -> 1).single();
        for (var v : verified) // refuse BEFORE any write if a rename would orphan a saved item
            if (v.existingId() != null) refuseIfSaved(v.existingId(), v.candidate().university(), slugs.get(v.candidate().key()));
        jdbc.sql("insert into catalog_release(id,country_code) values(:id,'PL') on conflict do nothing").param("id", PolonSources.RELEASE).update();
        for (var v : verified) {
            var c = v.candidate();
            long university = jdbc.sql("select id from university where slug=:s").param("s", c.university()).query(Long.class).optional()
                    .orElseThrow(() -> new IllegalStateException("University is absent; import universities-poland.json first: " + c.university()));
            String url = PolonSources.recordUrl(c.courseUuid());
            long source = jdbc.sql("""
                    insert into source(url,title,publisher,source_type,retrieved_on) values(:u,:t,:p,'GOVERNMENT',:d)
                    on conflict(url) do update set retrieved_on=excluded.retrieved_on returning id""")
                    .param("u", url).param("t", "POL-on official course record: " + v.program().name()).param("p", PUBLISHER)
                    .param("d", today).query(Long.class).single();
            long programId = v.existingId() != null ? v.existingId() : createProgram(university, v.program(), slugs.get(c.key()), today);
            if (v.existingId() != null) renameSlug(programId, c.university(), slugs.get(c.key()), source, now);
            jdbc.sql("insert into program_source(program_id,source_id) values(:p,:s) on conflict do nothing").param("p", programId).param("s", source).update();
            jdbc.sql("insert into university_source(university_id,source_id) values(:u,:s) on conflict do nothing").param("u", university).param("s", source).update();
            monitors.register(source, new SourceMonitor.Spec(url, university, programId, "PROGRAM", "name,level,field,duration,languages", "ADAPTER",
                    PolonAdapter.NAME, PolonAdapter.VERSION, props.generalIntervalDays()), "VERIFIED", now);
            approvedHosts.refresh();
            SourceMonitor monitor = monitors.findBySourceId(source).orElseThrow();
            String parser = PageCanonicalizer.VERSION + "+" + PolonAdapter.NAME + "@" + PolonAdapter.VERSION;
            var outcome = pipeline.process(monitor, PolonParser.toFacts(v.program()), parser, now, true);
            if (outcome.rejectedCount() > 0 || outcome.pendingReview() > 0)
                throw new IllegalStateException("POL-on facts need review before activation: " + c.key() + " " + outcome.rejected());
            programFacts.recordProvenance(programId, "name", source, now, parser);
            programFacts.recordProvenance(programId, "degree_level", source, now, parser);
            String hash = new PageCanonicalizer().canonicalize(v.record().body(), "application/json").hash();
            monitors.saveState(monitor.with(m -> {
                m.status = "ACTIVE"; m.failureCount = 0; m.unsupportedReason = null; m.lastAttemptAt = now; m.lastSuccessAt = now;
                m.nextCheckAt = CheckSchedule.next(now, monitor.checkIntervalDays(), false, props.deadlineIntervalDays(), 0);
                m.lastResult = "BASELINE"; m.contentHash = hash; m.hashParserVersion = parser;
            }));
            monitors.logCheck(source, now, "BASELINE", v.record().httpStatus(), hash, v.record().durationMs(), parser, null);
            jdbc.sql("""
                    insert into catalog_membership(release_id,program_id,entry_level,qualification_pathway,education_oid,implementation_oid)
                    values(:r,:p,:l,'SINGLE_DEGREE',:course,:instance) on conflict(release_id,program_id) do update
                    set entry_level=excluded.entry_level,education_oid=excluded.education_oid,implementation_oid=excluded.implementation_oid""")
                    .param("r", PolonSources.RELEASE).param("p", programId).param("l", c.entryLevel())
                    .param("course", c.courseUuid()).param("instance", c.courseInstanceUuid()).update(); // POL-on course/instance uuids in the identifier columns
            applyPages(v, programId, university, pages, now, today);
        }
        jdbc.sql("update catalog_release set enabled=true,approved_by='operator:reviewed-fingerprint',approved_at=:now where id=:r")
                .param("now", Timestamp.from(now)).param("r", PolonSources.RELEASE).update();
    }

    /** Each page read in the preview becomes a source with its own fact-extracting monitor; its facts go through the normal pipeline. */
    private void applyPages(Verified v, long programId, long university, java.util.Map<String, PageFetcher.Result> pages, Instant now, LocalDate today) {
        var c = v.candidate();
        for (String u : pageUrls(c)) {
            var r = pages.get(u);
            if (r == null) continue; // not read OK in the approved preview: no source, no fact
            var facts = PolishPageAdapter.extract(r.body(), r.finalUrl(), u, c);
            if (facts.isEmpty()) continue;
            long source = jdbc.sql("""
                    insert into source(url,title,publisher,source_type,retrieved_on) values(:u,:t,:p,'UNIVERSITY_WEBSITE',:d)
                    on conflict(url) do update set retrieved_on=excluded.retrieved_on returning id""")
                    .param("u", u).param("t", "Official page of " + v.program().institution()).param("p", v.program().institution())
                    .param("d", today).query(Long.class).single();
            jdbc.sql("insert into program_source(program_id,source_id) values(:p,:s) on conflict do nothing").param("p", programId).param("s", source).update();
            monitors.register(source, new SourceMonitor.Spec(u, university, null, "PROGRAM", "official page facts", "ADAPTER",
                    PolishPageAdapter.NAME, PolishPageAdapter.VERSION, props.generalIntervalDays()), "VERIFIED", now);
            approvedHosts.refresh();
            SourceMonitor monitor = monitors.findBySourceId(source).orElseThrow();
            String parser = "curated-pl-1+" + PolishPageAdapter.NAME + "@" + PolishPageAdapter.VERSION;
            var outcome = pipeline.process(monitor.withProgramId(programId), facts, parser, now, true);
            if (outcome.rejectedCount() > 0 || outcome.pendingReview() > 0)
                throw new IllegalStateException("Page facts need review before activation: " + c.key() + " " + u + " " + outcome.rejected());
            String hash = new PageCanonicalizer().canonicalize(r.body(), r.contentType()).hash();
            monitors.saveState(monitor.with(m -> {
                m.status = "ACTIVE"; m.failureCount = 0; m.unsupportedReason = null; m.lastAttemptAt = now; m.lastSuccessAt = now;
                m.nextCheckAt = CheckSchedule.next(now, monitor.checkIntervalDays(), false, props.deadlineIntervalDays(), 0);
                m.lastResult = "BASELINE"; m.contentHash = hash; m.hashParserVersion = parser;
            }));
            monitors.logCheck(source, now, "BASELINE", r.httpStatus(), hash, r.durationMs(), parser, null);
        }
    }

    private long createProgram(long university, PolonParser.Program p, String planned, LocalDate today) {
        String slug = planned;
        if (taken(university, slug)) slug = planned + "-" + p.degreeLevel().toLowerCase(Locale.ROOT); // a later same-name programme: level, never a number
        if (taken(university, slug)) throw new IllegalStateException("Programme slug already taken: " + slug);
        long id = jdbc.sql("""
                insert into degree_program(university_id,slug,name,degree_level,official_program_url,verification_status,verified_on)
                values(:u,:s,:n,:l,null,'VERIFIED',:d) returning id""")
                .param("u", university).param("s", slug).param("n", p.name()).param("l", p.degreeLevel()).param("d", today).query(Long.class).single();
        jdbc.sql("insert into program_external_id(program_id,scheme,value) values(:p,:s,:v)").param("p", id).param("s", PolonParser.SCHEME_COURSE).param("v", p.courseUuid()).update();
        jdbc.sql("insert into program_external_id(program_id,scheme,value) values(:p,:s,:v)").param("p", id).param("s", PolonParser.SCHEME_INSTANCE).param("v", p.instanceUuid()).update();
        return id;
    }

    private boolean taken(long university, String slug) {
        return jdbc.sql("select count(*) from degree_program where university_id=:u and slug=:s").param("u", university).param("s", slug).query(Long.class).single() > 0;
    }

    private void refuseIfSaved(long programId, String universitySlug, String planned) {
        String current = jdbc.sql("select slug from degree_program where id=:i").param("i", programId).query(String.class).single();
        if (current.equals(planned)) return;
        long saved = jdbc.sql("select count(*) from saved_program where program_identifier=:p").param("p", universitySlug + "--" + current).query(Long.class).single();
        if (saved > 0) throw new IllegalStateException(saved + " saved item(s) reference " + universitySlug + "--" + current + "; not renamed");
    }

    /** An explicit, archived rename of an identifier nobody has saved; refused if any saved item still points at the old one. */
    private void renameSlug(long programId, String universitySlug, String planned, long source, Instant now) {
        String current = jdbc.sql("select slug from degree_program where id=:i").param("i", programId).query(String.class).single();
        if (current.equals(planned)) return;
        long university = jdbc.sql("select university_id from degree_program where id=:i").param("i", programId).query(Long.class).single();
        if (taken(university, planned)) throw new IllegalStateException("Cannot rename " + current + ": " + planned + " is taken");
        refuseIfSaved(programId, universitySlug, planned);
        jdbc.sql("insert into program_fact_history(program_id,field_name,value,recorded_source_id,superseded_by_source,note) values(:p,'slug',:v,null,:s,:n)")
                .param("p", programId).param("v", current).param("s", source).param("n", "renamed to " + planned + ": same-name programmes of a university carry the degree level, not a number").update();
        jdbc.sql("update degree_program set slug=:s where id=:i").param("s", planned).param("i", programId).update();
    }

    /** Lower-case words joined by single hyphens (the program identifier relies on "--" never appearing in a slug). */
    static String slugify(String name) {
        String s = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT).replace("ł", "l").replaceAll("['’]", "").replaceAll("\\(.*?\\)", " ").replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (s.length() > 100) s = s.substring(0, 100).replaceAll("-+[^-]*$", "").replaceAll("-+$", "");
        return s.isEmpty() ? "program" : s;
    }
}

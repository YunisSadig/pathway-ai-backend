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
                    String pageStatus, String feesStatus, Long existingId) { }
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

    record Plan(List<Verified> verified, List<Skipped> skipped, String fingerprint) { }

    @Override
    public void run(ApplicationArguments args) {
        if (!List.of("preview", "apply").contains(command))
            throw new IllegalArgumentException("Poland command must be preview or apply");
        var plan = plan();
        List<Verified> verified = plan.verified();
        List<Skipped> skipped = plan.skipped();
        String today = LocalDate.now(clock).toString();
        for (var v : verified) printVerified(v, today);
        for (var s : skipped) System.out.println("POLAND_SKIPPED " + s.candidate().key() + " reason=" + s.reason());
        String fingerprint = plan.fingerprint();
        System.out.println("POLAND_PLAN release=" + PolonSources.RELEASE + " candidates=" + sources.candidates().size() + " importable=" + verified.size()
                + " skipped=" + skipped.size() + " newPrograms=" + verified.stream().filter(v -> v.existingId() == null).count()
                + " fingerprint=" + fingerprint);
        if (command.equals("preview")) { System.out.println("POLAND_PREVIEW no writes, no activation, cataloguePublic unchanged"); return; }
        if (!fingerprint.equals(expected)) throw new IllegalArgumentException("Plan differs from the approved fingerprint; run and review a fresh preview");
        transaction.executeWithoutResult(status -> apply(verified));
        System.out.println("POLAND_APPLIED " + PolonSources.RELEASE + " " + verified.size() + " programmes; nothing was deleted");
    }

    /** Read-only: reads POL-on and the universities' pages, writes nothing. */
    Plan plan() {
        List<Verified> verified = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        for (var c : sources.candidates()) {
            try {
                if (!c.identityConfirmed()) { skipped.add(new Skipped(c, "identity mapping unconfirmed (POL-on name \"" + c.polonName() + "\" differs from the university page): a person must confirm it")); continue; }
                var record = fetch(PolonSources.recordUrl(c.courseUuid()));
                var program = PolonParser.parse(record.body(), c, taxonomy);
                String page = c.programUrl() == null ? "NOT STATED (no live official programme page found)" : check(c.programUrl(), c);
                String fees = c.feesUrl() == null ? "none listed" : check(c.feesUrl(), c);
                Long existing = jdbc.sql("select program_id from program_external_id where scheme=:s and value=:v")
                        .param("s", PolonParser.SCHEME_COURSE).param("v", c.courseUuid()).query(Long.class).optional().orElse(null);
                verified.add(new Verified(c, program, record, page, fees, existing));
            } catch (RuntimeException e) {
                skipped.add(new Skipped(c, "not verified: " + e.getMessage()));
            }
        }
        return new Plan(verified, skipped, fingerprint(verified));
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
        System.out.println("  MISSING tuition=" + NOT_STATED + "; deadlines=" + NOT_STATED + "; admission requirements=" + NOT_STATED + "; application URL=" + NOT_STATED);
    }

    /** The page must load over https from the university's own registered domain; its content is not interpreted. */
    private String check(String url, PolonSources.Candidate c) {
        URI uri = URI.create(url);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equals(uri.getScheme()) || !(host.equals(c.officialDomain()) || host.endsWith("." + c.officialDomain())))
            throw new IllegalStateException("not an official https page of " + c.officialDomain() + ": " + url);
        var r = fetcher.fetch(url, null, null);
        // an unreadable page never blocks the programme (POL-on states it); the page is simply not recorded as verified
        return r.kind() == PageFetcher.Kind.OK ? "OK " + r.httpStatus() : "NOT VERIFIED (" + r.kind() + ")";
    }

    private PageFetcher.Result fetch(String url) {
        var r = fetcher.fetch(url, null, null);
        if (r.kind() != PageFetcher.Kind.OK) throw new IllegalStateException("POL-on record not readable: " + r.kind() + " " + r.error());
        return r;
    }

    private String fingerprint(List<Verified> verified) {
        List<String> rows = new ArrayList<>();
        for (var v : verified) {
            rows.add(v.candidate().courseUuid() + "|" + v.candidate().courseInstanceUuid() + "|" + v.program().name() + "|" + v.program().degreeLevel());
            PolonParser.toFacts(v.program()).forEach(f -> rows.add(f.type() + f.key() + f.canonicalValue()));
        }
        rows.sort(String::compareTo);
        return PageCanonicalizer.sha256(String.join("\n", rows));
    }

    private void apply(List<Verified> verified) {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        jdbc.sql("select pg_advisory_xact_lock(721681204)").query((rs, n) -> 1).single();
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
            long programId = v.existingId() != null ? v.existingId() : createProgram(university, v.program(), c, source, today);
            jdbc.sql("insert into program_source(program_id,source_id) values(:p,:s) on conflict do nothing").param("p", programId).param("s", source).update();
            jdbc.sql("insert into university_source(university_id,source_id) values(:u,:s) on conflict do nothing").param("u", university).param("s", source).update();
            if (v.pageStatus().startsWith("OK")) { // only a page read in this very preview is recorded as a source
                long page = jdbc.sql("""
                        insert into source(url,title,publisher,source_type,retrieved_on) values(:u,:t,:p,'UNIVERSITY_WEBSITE',:d)
                        on conflict(url) do update set retrieved_on=excluded.retrieved_on returning id""")
                        .param("u", c.programUrl()).param("t", "Programme page: " + v.program().name()).param("p", v.program().institution())
                        .param("d", today).query(Long.class).single();
                jdbc.sql("insert into program_source(program_id,source_id) values(:p,:s) on conflict do nothing").param("p", programId).param("s", page).update();
            }
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
        }
        jdbc.sql("update catalog_release set enabled=true,approved_by='operator:reviewed-fingerprint',approved_at=:now where id=:r")
                .param("now", Timestamp.from(now)).param("r", PolonSources.RELEASE).update();
    }

    private long createProgram(long university, PolonParser.Program p, PolonSources.Candidate c, long source, LocalDate today) {
        String slug = slugify(p.name());
        if (jdbc.sql("select count(*) from degree_program where university_id=:u and slug=:s").param("u", university).param("s", slug).query(Long.class).single() > 0)
            slug = slug + "-" + p.courseCode();
        long id = jdbc.sql("""
                insert into degree_program(university_id,slug,name,degree_level,official_program_url,verification_status,verified_on)
                values(:u,:s,:n,:l,null,'VERIFIED',:d) returning id""")
                .param("u", university).param("s", slug).param("n", p.name()).param("l", p.degreeLevel()).param("d", today).query(Long.class).single();
        jdbc.sql("insert into program_external_id(program_id,scheme,value) values(:p,:s,:v)").param("p", id).param("s", PolonParser.SCHEME_COURSE).param("v", p.courseUuid()).update();
        jdbc.sql("insert into program_external_id(program_id,scheme,value) values(:p,:s,:v)").param("p", id).param("s", PolonParser.SCHEME_INSTANCE).param("v", p.instanceUuid()).update();
        return id;
    }

    /** Lower-case words joined by single hyphens (the program identifier relies on "--" never appearing in a slug). */
    static String slugify(String name) {
        String s = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT).replace("ł", "l").replaceAll("['’]", "").replaceAll("\\(.*?\\)", " ").replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (s.length() > 100) s = s.substring(0, 100).replaceAll("-+[^-]*$", "").replaceAll("-+$", "");
        return s.isEmpty() ? "program" : s;
    }
}

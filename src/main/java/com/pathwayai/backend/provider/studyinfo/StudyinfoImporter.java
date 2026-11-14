package com.pathwayai.backend.provider.studyinfo;

import com.pathwayai.backend.refresh.RefreshProperties;
import com.pathwayai.backend.refresh.extract.ExtractedFact;
import com.pathwayai.backend.refresh.extract.PageCanonicalizer;
import com.pathwayai.backend.refresh.extract.PageCanonicalizer.CanonicalPage;
import com.pathwayai.backend.refresh.fetch.ApprovedHosts;
import com.pathwayai.backend.refresh.fetch.PageFetcher;
import com.pathwayai.backend.refresh.pipeline.ChangePipeline;
import com.pathwayai.backend.refresh.pipeline.ProgramFacts;
import com.pathwayai.backend.refresh.registry.SourceMonitor;
import com.pathwayai.backend.refresh.registry.SourceMonitorRepository;
import com.pathwayai.backend.refresh.schedule.CheckSchedule;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Operator-run import of degree programs from the Studyinfo official API for a short, reviewed list of universities
 * ({@code reference-data/studyinfo.json}). It discovers a university's published offerings, reads each program's full
 * record, and creates a program only when every identity fact is explicit. Creation goes through the same
 * parse, validate and stage pipeline as every later refresh, and registers a monitor that re-reads the same record on
 * schedule. Importing twice creates nothing new: a program already carrying the provider's identifier is skipped
 * without a request, so existing rows are never rewritten here.
 */
@Service
public class StudyinfoImporter {

    private static final Logger log = LoggerFactory.getLogger(StudyinfoImporter.class);
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 8;
    private static final String ENGLISH = "oppilaitoksenopetuskieli_4";
    /** Records fetched beyond the quota (to replace skipped ones) before a university is given up on for this run. */
    private static final int EXTRA_ATTEMPTS = 6;

    public record Provider(String name, String publisher, String apiBase, String programPageBase, String programPageFallbackBase) {
    }

    public record CreateIfMissing(String name, String countryCode, String city) {
    }

    public record UniversityConfig(String slug, String organisationOid, String officialName, int maxPrograms,
                                   CreateIfMissing createIfMissing) {
    }

    public record Config(Provider provider, List<UniversityConfig> universities) {
    }

    /** What one run did. {@code notes} carry every skip and failure with its reason. */
    public record Summary(int universities, int created, int alreadyImported, int skipped, int failed, List<String> notes) {
    }

    private final JdbcClient jdbc;
    private final PageFetcher fetcher;
    private final PageCanonicalizer canonicalizer;
    private final StudyinfoAdapter adapter;
    private final FieldTaxonomy taxonomy;
    private final ChangePipeline pipeline;
    private final ProgramFacts programFacts;
    private final SourceMonitorRepository monitors;
    private final ApprovedHosts approvedHosts;
    private final RefreshProperties props;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Config config;

    public StudyinfoImporter(JdbcClient jdbc, PageFetcher fetcher, PageCanonicalizer canonicalizer, StudyinfoAdapter adapter,
                             FieldTaxonomy taxonomy, ChangePipeline pipeline, ProgramFacts programFacts, SourceMonitorRepository monitors,
                             ApprovedHosts approvedHosts, RefreshProperties props, PlatformTransactionManager txManager,
                             Clock clock, ResourceLoader resources,
                             @Value("${app.studyinfo.config:classpath:reference-data/studyinfo.json}") String configLocation) {
        this.jdbc = jdbc;
        this.fetcher = fetcher;
        this.canonicalizer = canonicalizer;
        this.adapter = adapter;
        this.taxonomy = taxonomy;
        this.pipeline = pipeline;
        this.programFacts = programFacts;
        this.monitors = monitors;
        this.approvedHosts = approvedHosts;
        this.props = props;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.config = loadConfig(resources, configLocation);
    }

    private static Config loadConfig(ResourceLoader resources, String location) {
        try (InputStream in = resources.getResource(location).getInputStream()) {
            return JsonMapper.builder().build().readValue(in, Config.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Config config() {
        return config;
    }

    /** {@code onlySlug} and {@code limit} are optional operator narrowing; {@code dryRun} reads but writes nothing. */
    public Summary run(boolean dryRun, String onlySlug, Integer limit) {
        Instant now = clock.instant();
        List<String> notes = new ArrayList<>();
        int created = 0, existing = 0, skipped = 0, failed = 0, universities = 0;
        for (UniversityConfig u : config.universities()) {
            if (onlySlug != null && !onlySlug.equals(u.slug())) {
                continue;
            }
            universities++;
            int quota = limit != null ? Math.min(limit, u.maxPrograms()) : u.maxPrograms();
            Long universityId = dryRun ? lookupUniversity(u.slug()) : ensureUniversity(u, notes);
            if (universityId == null && !(dryRun && u.createIfMissing() != null)) {
                notes.add(u.slug() + ": university is not in the catalogue and is not configured to be created; skipped");
                skipped++;
                continue;
            }
            List<Hit> hits;
            try {
                hits = discover(u, notes);
            } catch (IllegalStateException e) {
                notes.add(u.slug() + ": discovery failed: " + e.getMessage());
                failed++;
                continue;
            }
            int handled = 0;
            int attempts = 0;
            for (Hit hit : hits) {
                if (handled >= quota) {
                    break;
                }
                if (attempts >= quota + EXTRA_ATTEMPTS) {
                    notes.add(u.slug() + ": stopped after " + attempts + " records fetched without reaching " + quota + " importable programs");
                    break;
                }
                if (universityId != null && programExists(hit.koulutusOid())) {
                    existing++;
                    handled++;
                    continue;
                }
                attempts++;
                Fetched f = fetchRecord(hit.koulutusOid());
                if (f.error() != null) {
                    notes.add(u.slug() + " " + hit.koulutusOid() + ": " + f.error());
                    failed++;
                    continue;
                }
                StudyinfoParser.Parsed parsed = StudyinfoParser.parse(f.body(), taxonomy);
                String why = unusable(parsed, u, hit.institutionOid());
                if (why != null) {
                    notes.add(u.slug() + " " + hit.koulutusOid() + " (" + hit.name() + "): skipped, " + why);
                    skipped++;
                    continue;
                }
                StudyinfoParser.Program p = parsed.program();
                if (dryRun) {
                    notes.add(u.slug() + " WOULD IMPORT " + hit.koulutusOid() + " " + p.degreeLevel() + " \"" + p.name() + "\" field="
                            + p.fieldSlug() + " months=" + p.durationMonths() + " lang=" + p.languages() + " city=" + p.campusCity()
                            + " deadlines=" + p.deadlines().size() + (parsed.issues().isEmpty() ? "" : " left out: " + parsed.issues()));
                    handled++;
                    continue;
                }
                try {
                    long uid = universityId;
                    tx.executeWithoutResult(s -> createProgram(uid, p, f, now));
                    created++;
                    handled++;
                    notes.add(u.slug() + " IMPORTED " + p.koulutusOid() + " " + p.degreeLevel() + " \"" + p.name() + "\""
                            + (parsed.issues().isEmpty() ? "" : " (left out: " + parsed.issues() + ")"));
                } catch (RuntimeException e) {
                    log.warn("Import of {} failed and was rolled back: {}", hit.koulutusOid(), e.toString());
                    notes.add(u.slug() + " " + hit.koulutusOid() + ": rolled back: " + e.getMessage());
                    failed++;
                }
            }
        }
        return new Summary(universities, created, existing, skipped, failed, notes);
    }

    // ---- discovery ----------------------------------------------------------------------------------------------

    /** {@code institutionOid} is the institution the provider's own search listed the offering under. */
    private record Hit(String koulutusOid, String name, String institutionOid) {
    }

    @SuppressWarnings("unchecked")
    private List<Hit> discover(UniversityConfig u, List<String> notes) {
        List<Hit> hits = new ArrayList<>();
        int total = Integer.MAX_VALUE;
        int seen = 0;
        for (int page = 1; page <= MAX_PAGES && seen < total; page++) {
            String url = config.provider().apiBase() + "/search/oppilaitos/" + u.organisationOid()
                    + "/tarjonta?lng=en&order=asc&size=" + PAGE_SIZE + "&page=" + page;
            PageFetcher.Result r = fetcher.fetch(url, null, null);
            log.info("Studyinfo discovery {} page {}: {} in {} ms", u.slug(), page, r.kind(), r.durationMs());
            if (r.kind() != PageFetcher.Kind.OK) {
                throw new IllegalStateException(r.kind() + " " + r.error());
            }
            Map<String, Object> root;
            try {
                root = JsonMapper.builder().build().readValue(r.body(), Map.class);
            } catch (RuntimeException e) {
                throw new IllegalStateException("search response is not valid JSON");
            }
            total = root.get("total") instanceof Number n ? n.intValue() : 0;
            List<Object> pageHits = root.get("hits") instanceof List<?> l ? (List<Object>) l : List.of();
            if (pageHits.isEmpty()) {
                break;
            }
            seen += pageHits.size();
            for (Object o : pageHits) {
                Map<String, Object> h = (Map<String, Object>) o;
                if (!"yo".equals(h.get("koulutustyyppi")) || !(h.get("koulutusOid") instanceof String oid)) {
                    continue;
                }
                if (!(h.get("opetuskielet") instanceof List<?> langs) || !langs.contains(ENGLISH)) {
                    continue; // this milestone imports English-taught programs, the ones an international applicant can use
                }
                String name = h.get("nimi") instanceof Map<?, ?> m && m.get("en") instanceof String en ? en.strip() : oid;
                if (name.toLowerCase(Locale.ROOT).matches(".*(doctoral|doctor of|\\bphd\\b|licentiate).*")) {
                    continue; // only saves a request: the degree level that counts is the one the record's codes state
                }
                hits.add(new Hit(oid, name, h.get("oppilaitosOid") instanceof String io ? io : null));
            }
        }
        hits.sort(Comparator.comparing(Hit::name).thenComparing(Hit::koulutusOid));
        notes.add(u.slug() + ": " + hits.size() + " English-taught university offerings considered (provider lists " + total + ")");
        return hits;
    }

    private record Fetched(String url, String body, String etag, String lastModified, long durationMs, String error) {
    }

    private Fetched fetchRecord(String koulutusOid) {
        String url = recordUrl(koulutusOid);
        PageFetcher.Result r = fetcher.fetch(url, null, null);
        log.info("Studyinfo record {}: {} in {} ms", koulutusOid, r.kind(), r.durationMs());
        if (r.kind() != PageFetcher.Kind.OK) {
            return new Fetched(url, null, null, null, r.durationMs(), r.kind() + " " + r.error());
        }
        return new Fetched(url, r.body(), r.etag(), r.lastModified(), r.durationMs(), null);
    }

    /** The one URL both the importer and the monitor read, so a refresh sees exactly what the import saw. */
    public String recordUrl(String koulutusOid) {
        return config.provider().apiBase() + "/koulutus/" + koulutusOid + "?toteutukset=true&hakukohteet=true&haut=true";
    }

    private static String unusable(StudyinfoParser.Parsed parsed, UniversityConfig u, String listedUnder) {
        if (parsed.program() == null) {
            return String.join("; ", parsed.issues());
        }
        StudyinfoParser.Program p = parsed.program();
        // The association rests on the provider's own statement: either the record names exactly this institution, or
        // the provider's search for this institution listed the offering and the record names a single provider (a
        // unit inside the university). A record naming another institution that the search did not list is refused.
        boolean namesUs = u.organisationOid().equals(p.organisationOid());
        boolean listedUnderUs = u.organisationOid().equals(listedUnder);
        if (!namesUs && !listedUnderUs) {
            return "the record's providing institution is " + p.organisationOid() + ", not " + u.organisationOid();
        }
        if (namesUs && p.organisationName() != null && !p.organisationName().equalsIgnoreCase(u.officialName())) {
            return "the record names the institution \"" + p.organisationName() + "\", expected \"" + u.officialName() + "\"";
        }
        if (p.degreeLevel() == null) {
            return "degree level is not a stated bachelor's or master's level";
        }
        return null;
    }

    // ---- writes -------------------------------------------------------------------------------------------------

    private Long lookupUniversity(String slug) {
        return jdbc.sql("select id from university where slug = :s").param("s", slug).query(Long.class).optional().orElse(null);
    }

    /** Finds the university, or creates it as UNVERIFIED when configured (identity then rests on the provider only). */
    private Long ensureUniversity(UniversityConfig u, List<String> notes) {
        Long id = lookupUniversity(u.slug());
        if (id == null && u.createIfMissing() != null) {
            CreateIfMissing c = u.createIfMissing();
            id = tx.execute(s -> jdbc.sql("""
                    insert into university (country_id, slug, name, city, verification_status)
                    select co.id, :slug, :name, :city, 'UNVERIFIED' from country co where co.iso2_code = :cc
                    returning id""")
                    .param("slug", u.slug()).param("name", c.name()).param("city", c.city()).param("cc", c.countryCode())
                    .query(Long.class).optional().orElse(null));
            if (id != null) {
                notes.add(u.slug() + ": created as UNVERIFIED (not in the EU registry catalogue; identity rests on the Studyinfo organisation record)");
            }
        }
        if (id == null) {
            return null;
        }
        Optional<Long> owner = jdbc.sql("select university_id from university_external_id where scheme = :sc and value = :v")
                .param("sc", StudyinfoParser.SCHEME_ORGANISATION).param("v", u.organisationOid()).query(Long.class).optional();
        if (owner.isPresent() && !owner.get().equals(id)) {
            throw new IllegalStateException("organisation " + u.organisationOid() + " is already linked to a different university");
        }
        if (owner.isEmpty()) {
            long uid = id;
            tx.executeWithoutResult(s -> jdbc.sql("insert into university_external_id (university_id, scheme, value) values (:u, :sc, :v)")
                    .param("u", uid).param("sc", StudyinfoParser.SCHEME_ORGANISATION).param("v", u.organisationOid()).update());
        }
        return id;
    }

    private boolean programExists(String koulutusOid) {
        return jdbc.sql("select count(*) from program_external_id where scheme = :s and value = :v")
                .param("s", StudyinfoParser.SCHEME_KOULUTUS).param("v", koulutusOid).query(Long.class).single() > 0;
    }

    private void createProgram(long universityId, StudyinfoParser.Program p, Fetched f, Instant now) {
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        String base = slugify(p.name());
        String slug = base;
        if (jdbc.sql("select count(*) from degree_program where university_id = :u and slug = :s")
                .param("u", universityId).param("s", slug).query(Long.class).single() > 0) {
            String oid = p.koulutusOid();
            slug = base + "-" + oid.substring(Math.max(0, oid.length() - 6));
        }
        String pageUrl = p.toteutusOid() != null ? config.provider().programPageBase() + p.toteutusOid()
                : config.provider().programPageFallbackBase() + p.koulutusOid();
        long sourceId = jdbc.sql("""
                insert into source (url, title, publisher, source_type, retrieved_on)
                values (:url, :title, :pub, 'GOVERNMENT', :on)
                on conflict (url) do update set retrieved_on = excluded.retrieved_on
                returning id""")
                .param("url", f.url()).param("title", truncate("Studyinfo official API record: " + p.name(), 300))
                .param("pub", config.provider().publisher()).param("on", today).query(Long.class).single();
        long programId = jdbc.sql("""
                insert into degree_program (university_id, slug, name, degree_level, official_program_url,
                                            verification_status, verified_on)
                values (:u, :slug, :name, :level, :url, 'VERIFIED', :on) returning id""")
                .param("u", universityId).param("slug", slug).param("name", p.name()).param("level", p.degreeLevel())
                .param("url", pageUrl).param("on", today).query(Long.class).single();
        jdbc.sql("insert into program_external_id (program_id, scheme, value) values (:p, :s, :v)")
                .param("p", programId).param("s", StudyinfoParser.SCHEME_KOULUTUS).param("v", p.koulutusOid()).update();
        if (p.toteutusOid() != null) {
            jdbc.sql("insert into program_external_id (program_id, scheme, value) values (:p, :s, :v)")
                    .param("p", programId).param("s", StudyinfoParser.SCHEME_TOTEUTUS).param("v", p.toteutusOid()).update();
        }
        jdbc.sql("insert into program_source (program_id, source_id) values (:p, :s) on conflict do nothing")
                .param("p", programId).param("s", sourceId).update();
        jdbc.sql("insert into university_source (university_id, source_id) values (:u, :s) on conflict do nothing")
                .param("u", universityId).param("s", sourceId).update();

        monitors.register(sourceId, new SourceMonitor.Spec(f.url(), universityId, programId, "PROGRAM",
                "name,level,field,campus,duration,languages,deadline", "ADAPTER", StudyinfoAdapter.NAME,
                StudyinfoAdapter.VERSION, props.generalIntervalDays()), "VERIFIED", now);
        approvedHosts.refresh();
        SourceMonitor monitor = monitors.findBySourceId(sourceId).orElseThrow();
        CanonicalPage page = canonicalizer.canonicalize(f.body(), "application/json");
        String parser = PageCanonicalizer.VERSION + "+" + adapter.name() + "@" + adapter.version();
        List<ExtractedFact> facts = StudyinfoParser.toFacts(p);
        ChangePipeline.Outcome outcome = pipeline.process(monitor, facts, parser, now, true);
        if (outcome.rejectedCount() > 0) {
            throw new IllegalStateException("a fact failed validation: " + outcome.rejected());
        }
        // name and level were set when the row was created from this very record, so no fact was applied for them
        programFacts.recordProvenance(programId, "name", sourceId, now, parser);
        programFacts.recordProvenance(programId, "degree_level", sourceId, now, parser);
        monitors.saveState(monitor.with(m -> {
            m.lastAttemptAt = now;
            m.lastSuccessAt = now;
            m.failureCount = 0;
            m.etag = f.etag();
            m.lastModified = f.lastModified();
            m.contentHash = page.hash();
            m.hashParserVersion = parser;
            m.lastResult = "BASELINE";
            m.nextCheckAt = CheckSchedule.next(now, monitor.checkIntervalDays(), false, props.deadlineIntervalDays(), 0);
        }));
        monitors.logCheck(sourceId, now, "BASELINE", 200, page.hash(), f.durationMs(), parser, null);
    }

    /** Lower-case words joined by single hyphens (the program identifier relies on "--" never appearing in a slug). */
    static String slugify(String name) {
        String s = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT).replaceAll("['’]", "").replaceAll("\\(.*?\\)", " ").replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (s.length() > 100) {
            s = s.substring(0, 100).replaceAll("-+[^-]*$", "").replaceAll("-+$", "");
        }
        return s.isEmpty() ? "program" : s;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}

package com.pathwayai.backend.provider.polon;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The reviewed Polish candidates ({@code reference-data/curated-poland.json}). Each names one POL-on course and the one
 * course instance (English, full-time) that is meant. The file holds identifiers and the universities' own page URLs only;
 * every academic fact is read from POL-on at import and refresh time, never copied from this file.
 */
@Component
public class PolonSources {
    public static final String COURSES_URL = "https://radon.nauka.gov.pl/opendata/polon/courses";
    public static final String RELEASE = "poland-curated-v1";

    /**
     * @param identityConfirmed a person confirmed that the POL-on course is the programme the university page describes
     *                          (the POL-on name is Polish or differs from the English page); unconfirmed candidates are never imported
     */
    public record Candidate(String key, String university, String courseUuid, String courseInstanceUuid, String entryLevel,
                            String programUrl, String feesUrl, boolean identityConfirmed, String polonName, String officialDomain,
                            String pageMatch, String feeStrategy, String feeRow) {
        /** The page that states this programme's fees: the fees page, else the programme page itself. */
        public String feePageUrl() {
            return feesUrl != null ? feesUrl : programUrl;
        }
    }

    private final List<Candidate> candidates;

    public PolonSources() {
        this(load("reference-data/curated-poland.json"));
    }

    PolonSources(List<Candidate> candidates) {
        this.candidates = List.copyOf(candidates);
        Set<String> keys = new HashSet<>(), courses = new HashSet<>(), instances = new HashSet<>();
        for (Candidate c : this.candidates) {
            if (!keys.add(c.key()) || !courses.add(c.courseUuid()) || !instances.add(c.courseInstanceUuid()))
                throw new IllegalStateException("Duplicate Polish candidate: " + c.key());
            if (!List.of("BACHELOR", "MASTER").contains(c.entryLevel()))
                throw new IllegalStateException("Unknown entry level for " + c.key());
        }
    }

    private static volatile java.util.Map<String, String> domains;

    private static java.util.Map<String, String> domains() {
        var d = domains;
        if (d == null) {
            var m = new java.util.HashMap<String, String>();
            for (Candidate c : load("reference-data/curated-poland.json")) m.put(c.university(), c.officialDomain());
            domains = d = java.util.Map.copyOf(m);
        }
        return d;
    }

    /** An https page of this university's own registered domain (a page URL stored as a fact must satisfy this). */
    public static boolean owned(String url, String university) {
        String domain = domains().get(university);
        if (url == null || domain == null) return false;
        try {
            var uri = java.net.URI.create(url);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
            return "https".equals(uri.getScheme()) && uri.getUserInfo() == null && uri.getPort() == -1
                    && (host.equals(domain) || host.endsWith("." + domain));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static boolean ownedByAny(String url) {
        return domains().keySet().stream().anyMatch(u -> owned(url, u));
    }

    public List<Candidate> candidates() {
        return candidates;
    }

    public Optional<Candidate> byCourseUuid(String uuid) {
        return candidates.stream().filter(c -> c.courseUuid().equals(uuid)).findFirst();
    }

    /** The official record of one course. It lists every instance of the course; the candidate names the reviewed one. */
    public static String recordUrl(String courseUuid) {
        return COURSES_URL + "?resultNumbers=2&courseUuid=" + courseUuid;
    }

    static List<Candidate> load(String resource) {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            return List.of(JsonMapper.builder().build().readValue(in, Candidate[].class));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

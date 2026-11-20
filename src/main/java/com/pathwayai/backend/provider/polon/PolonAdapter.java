package com.pathwayai.backend.provider.polon;

import com.pathwayai.backend.provider.studyinfo.FieldTaxonomy;
import com.pathwayai.backend.refresh.extract.ExtractedFact;
import com.pathwayai.backend.refresh.extract.ExtractionAdapter;
import com.pathwayai.backend.refresh.extract.PageCanonicalizer.CanonicalPage;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Official-API tier adapter for POL-on: deterministic, explicit, bound to the reviewed course and instance. */
@Component
public class PolonAdapter implements ExtractionAdapter {
    public static final String NAME = "polon-courses";
    public static final String VERSION = "1";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final PolonSources sources;
    private final FieldTaxonomy taxonomy;

    public PolonAdapter(PolonSources sources, FieldTaxonomy taxonomy) {
        this.sources = sources;
        this.taxonomy = taxonomy;
    }

    @Override public String name() { return NAME; }

    @Override public String version() { return VERSION; }

    @Override
    public List<ExtractedFact> extract(CanonicalPage page) {
        var candidate = candidate(page.text());
        return PolonParser.toFacts(PolonParser.parse(page.text(), candidate, taxonomy));
    }

    @Override
    public Optional<String> discontinuation(CanonicalPage page) {
        try {
            return PolonParser.discontinuation(page.text(), candidate(page.text()));
        } catch (RuntimeException e) {
            return Optional.empty(); // an unreadable response is a failed check, never a discontinuation
        }
    }

    private PolonSources.Candidate candidate(String body) {
        String uuid = JSON.readTree(body).path("results").path(0).path("courseUuid").asText();
        return sources.byCourseUuid(uuid).orElseThrow(() -> new IllegalStateException("POL-on course is not a reviewed candidate: " + uuid));
    }
}

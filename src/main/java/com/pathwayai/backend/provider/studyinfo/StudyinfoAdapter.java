package com.pathwayai.backend.provider.studyinfo;

import com.pathwayai.backend.refresh.extract.ExtractedFact;
import com.pathwayai.backend.refresh.extract.ExtractionAdapter;
import com.pathwayai.backend.refresh.extract.PageCanonicalizer.CanonicalPage;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads program facts from the Studyinfo official API (see {@link StudyinfoParser}). It is the "official API" tier of
 * the adapter policy: structured, explicit, deterministic. Bump {@link #version()} whenever the parser or the field
 * mapping could change what it returns, so the monitor re-baselines instead of reporting a phantom change.
 */
@Component
public class StudyinfoAdapter implements ExtractionAdapter {

    public static final String NAME = "studyinfo-external";
    public static final String VERSION = "2";

    private static final Logger log = LoggerFactory.getLogger(StudyinfoAdapter.class);

    private final FieldTaxonomy taxonomy;

    public StudyinfoAdapter(FieldTaxonomy taxonomy) {
        this.taxonomy = taxonomy;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public java.util.Optional<String> discontinuation(CanonicalPage page) {
        return StudyinfoParser.discontinuation(page.text());
    }

    @Override
    public List<ExtractedFact> extract(CanonicalPage page) {
        StudyinfoParser.Parsed parsed = StudyinfoParser.parse(page.text(), taxonomy);
        if (!parsed.issues().isEmpty()) {
            log.info("Studyinfo record left some facts out: {}", parsed.issues());
        }
        return parsed.program() == null ? List.of() : StudyinfoParser.toFacts(parsed.program());
    }
}

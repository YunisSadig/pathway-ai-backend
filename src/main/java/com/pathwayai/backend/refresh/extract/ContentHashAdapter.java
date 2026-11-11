package com.pathwayai.backend.refresh.extract;

import com.pathwayai.backend.refresh.extract.PageCanonicalizer.CanonicalPage;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The default adapter for a source that has no structured data or reviewed adapter. It extracts nothing: the page is
 * watched for change, and a change is reported for human review rather than guessed at.
 */
@Component
public class ContentHashAdapter implements ExtractionAdapter {

    public static final String NAME = "content-hash";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String version() {
        return "1";
    }

    @Override
    public List<ExtractedFact> extract(CanonicalPage page) {
        return List.of();
    }
}

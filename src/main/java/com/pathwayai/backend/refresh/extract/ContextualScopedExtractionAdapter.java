package com.pathwayai.backend.refresh.extract;

import com.pathwayai.backend.refresh.registry.SourceMonitor;
import java.util.List;
import java.util.Map;

/** A site-specific shared page whose interpretation depends on its registered university and purpose. */
public interface ContextualScopedExtractionAdapter extends ScopedExtractionAdapter {
    Map<String,List<ExtractedFact>> extractHtml(String html,String finalUrl,SourceMonitor monitor);
    @Override default Map<String,List<ExtractedFact>> extractForPrograms(PageCanonicalizer.CanonicalPage page) {
        throw new IllegalStateException("Source context is required");
    }
}

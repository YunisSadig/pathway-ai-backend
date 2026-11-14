package com.pathwayai.backend.refresh.extract;

import com.pathwayai.backend.refresh.extract.PageCanonicalizer.CanonicalPage;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * For pages that change constantly without any fact changing, chiefly a university's home page (news, events,
 * banners). Changes are recorded in the check history and the monitoring report, but never raise a review item:
 * that would bury the queue. An institution's identity is watched through its registry record instead.
 */
@Component
public class ContentWatchAdapter implements ExtractionAdapter {

    public static final String NAME = "content-watch";

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

    @Override
    public boolean raisesReviewItems() {
        return false;
    }
}

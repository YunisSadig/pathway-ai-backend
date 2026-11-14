package com.pathwayai.backend.refresh.extract;

import com.pathwayai.backend.refresh.extract.PageCanonicalizer.CanonicalPage;
import java.util.List;

/**
 * Reads facts from one kind of official source. A monitor names its adapter; there is deliberately no universal
 * HTML scraper, because universities do not share a page structure. Adapters must be deterministic, must return
 * only what the page states (never a guess) and must bump {@link #version()} whenever their output could change.
 *
 * <p>Preferred order: official API or feed, then structured data embedded in the page (JSON-LD), and only then a
 * per-site HTML adapter written for that site's actual markup.
 */
public interface ExtractionAdapter {

    String name();

    String version();

    List<ExtractedFact> extract(CanonicalPage page);

    /**
     * Whether a changed page from which this adapter extracts nothing should raise a human review item. True for
     * sources where any change may matter (admissions, tuition, programme pages); false for pages that change
     * constantly without a fact changing.
     */
    default boolean raisesReviewItems() {
        return true;
    }

    /**
     * When the source itself states, explicitly, that the program no longer exists (for Studyinfo: the record's state is
     * archived or deleted), the reason. Absence of a page, a timeout or an error is NEVER a discontinuation: those are
     * failures and leave the program as it was. Empty for every adapter that cannot tell.
     */
    default java.util.Optional<String> discontinuation(CanonicalPage page) {
        return java.util.Optional.empty();
    }
}

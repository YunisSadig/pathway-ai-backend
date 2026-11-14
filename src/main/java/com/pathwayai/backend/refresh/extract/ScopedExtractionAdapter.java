package com.pathwayai.backend.refresh.extract;

import com.pathwayai.backend.refresh.extract.PageCanonicalizer.CanonicalPage;
import java.util.List;
import java.util.Map;

/**
 * An adapter for an official page that states facts for several programs of one university (for example a tuition
 * table). It returns facts per program slug; the refresh service resolves each slug to a program of the monitor's
 * university and runs the normal pipeline for it, so every program's change is staged and reviewed on its own.
 * A slug the university does not have locally is ignored: nothing is created from a table row.
 *
 * <p>Keys of the returned facts must be unique per program fact; the program slug is part of the key so that two
 * programs with the same value never collapse into one staged change.
 */
public interface ScopedExtractionAdapter extends ExtractionAdapter {

    Map<String, List<ExtractedFact>> extractForPrograms(CanonicalPage page);

    /** Not used for a scoped adapter: facts are always returned per program. */
    @Override
    default List<ExtractedFact> extract(CanonicalPage page) {
        return List.of();
    }
}

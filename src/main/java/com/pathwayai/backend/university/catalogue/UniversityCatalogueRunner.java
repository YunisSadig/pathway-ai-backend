package com.pathwayai.backend.university.catalogue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * Runs the idempotent institution import at startup, only when explicitly enabled:
 * {@code IMPORT_UNIVERSITY_CATALOGUE=true} (property {@code app.catalogue.import-on-startup}). Run it before or after
 * the program reference-data import; both are idempotent and neither overwrites the other's facts.
 * A failed import stops startup, so a bad file is never half-applied and ignored.
 */
@Component
@Order(10)
@ConditionalOnProperty(name = "app.catalogue.import-on-startup", havingValue = "true")
public class UniversityCatalogueRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UniversityCatalogueRunner.class);

    private final UniversityCatalogueImporter importer;
    private final ResourceLoader resources;
    private final String location;

    public UniversityCatalogueRunner(UniversityCatalogueImporter importer, ResourceLoader resources,
                                     @Value("${app.catalogue.location}") String location) {
        this.importer = importer;
        this.resources = resources;
        this.location = location;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        var report = importer.importFrom(resources.getResource(location).getInputStream());
        log.info("University catalogue imported from {}: {} changes {}", location, report.total(), report.changes());
    }
}

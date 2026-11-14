package com.pathwayai.backend.program.importer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * Runs the idempotent reference-data import at startup, only when explicitly enabled:
 * {@code IMPORT_REFERENCE_DATA=true} (property {@code app.reference-data.import-on-startup}).
 * The file defaults to the bundled {@code classpath:reference-data/programs.json}; point
 * {@code REFERENCE_DATA_LOCATION} at any Spring resource (for example {@code file:/path/data.json}) to override.
 * A failed import stops startup, so bad data can never be half-applied and ignored.
 */
@Component
@ConditionalOnProperty(name = "app.reference-data.import-on-startup", havingValue = "true")
public class ReferenceDataImportRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ReferenceDataImportRunner.class);

    private final ReferenceDataImporter importer;
    private final ResourceLoader resources;
    private final String location;

    public ReferenceDataImportRunner(ReferenceDataImporter importer, ResourceLoader resources,
                                     @Value("${app.reference-data.location}") String location) {
        this.importer = importer;
        this.resources = resources;
        this.location = location;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        var report = importer.importFrom(resources.getResource(location).getInputStream());
        log.info("Reference data imported from {}: {} new rows {}", location, report.totalCreated(), report.created());
    }
}

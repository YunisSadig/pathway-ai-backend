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
 * The file defaults to the bundled taxonomy {@code classpath:reference-data/taxonomy.json} (countries and academic fields only); point
 * {@code REFERENCE_DATA_LOCATION} at any Spring resource (for example {@code file:/path/data.json}) to override.
 * A failed import stops startup, so bad data can never be half-applied and ignored.
 *
 * <p><b>No static academic data.</b> A file that carries programs, universities, fees, deadlines or requirements is refused
 * unless {@code app.reference-data.allow-static-records=true} is set explicitly (tests and one-off operator imports). Static
 * records are not kept current by any connector, so they must never enter the catalogue by accident.
 */
@Component
@ConditionalOnProperty(name = "app.reference-data.import-on-startup", havingValue = "true")
public class ReferenceDataImportRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ReferenceDataImportRunner.class);

    private final ReferenceDataImporter importer;
    private final ResourceLoader resources;
    private final String location;
    private final boolean allowStaticRecords;

    public ReferenceDataImportRunner(ReferenceDataImporter importer, ResourceLoader resources,
                                     @Value("${app.reference-data.location}") String location,
                                     @Value("${app.reference-data.allow-static-records:false}") boolean allowStaticRecords) {
        this.importer = importer;
        this.resources = resources;
        this.location = location;
        this.allowStaticRecords = allowStaticRecords;
    }

    static boolean hasStaticRecords(ReferenceData d) {
        return nonEmpty(d.programs()) || nonEmpty(d.universities()) || nonEmpty(d.sources()) || nonEmpty(d.retractions());
    }

    private static boolean nonEmpty(java.util.List<?> l) {
        return l != null && !l.isEmpty();
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        ReferenceData data = ReferenceDataImporter.parse(resources.getResource(location).getInputStream());
        if (!allowStaticRecords && hasStaticRecords(data)) {
            throw new IllegalStateException("Refusing to import " + location + ": it carries programs, universities or sources."
                    + " Static academic records are not synchronised automatically and are not allowed in the catalogue"
                    + " (set app.reference-data.allow-static-records=true only for a deliberate one-off import).");
        }
        var report = importer.importData(data);
        log.info("Reference data imported from {}: {} new rows {}", location, report.totalCreated(), report.created());
    }
}

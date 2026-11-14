package com.pathwayai.backend.program.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * Guards the SHIPPED reference data (no database needed). The bundled file is TAXONOMY ONLY. A static program,
 * university, fee, deadline or requirement has no extractor that keeps it current, so it must never ship in the
 * catalogue: this test fails the build if one is added, which is what stops obsolete seed records from coming back.
 */
class ReferenceDataFileTest {

    static ReferenceData data;

    @BeforeAll
    static void load() throws IOException {
        data = ReferenceDataImporter.parse(new ClassPathResource("reference-data/taxonomy.json").getInputStream());
    }

    @Test
    void shippedFileIsValid() {
        assertThat(ReferenceDataValidator.validate(data)).isEmpty();
    }

    @Test
    void theShippedFileCarriesNoAcademicRecordsAtAll() {
        assertThat(data.programs()).as("static programs are not allowed").isEmpty();
        assertThat(data.universities()).as("universities come from the connector-backed catalogue").isEmpty();
        assertThat(data.sources()).isEmpty();
        assertThat(data.retractions()).isEmpty();
        assertThat(ReferenceDataImportRunner.hasStaticRecords(data)).isFalse();
    }

    @Test
    void theOldStaticProgramFileIsGone() {
        assertThat(new ClassPathResource("reference-data/programs.json").exists()).isFalse();
    }

    @Test
    void taxonomyIsCountriesAndFieldsOnly() {
        assertThat(data.fields()).isNotEmpty();
        assertThat(data.countries()).isNotEmpty();
    }

    @Test
    void theStartupRunnerRefusesAFileWithStaticRecordsUnlessExplicitlyAllowed() throws IOException {
        ReferenceData withProgram = ReferenceDataImporter.parse(new ClassPathResource("reference-data/synthetic.json").getInputStream());
        assertThat(ReferenceDataImportRunner.hasStaticRecords(withProgram)).isTrue();
        var runner = new ReferenceDataImportRunner(null, new org.springframework.core.io.DefaultResourceLoader(),
                "classpath:reference-data/synthetic.json", false);
        assertThatThrownBy(() -> runner.run(null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Static academic records are not synchronised automatically");
    }
}

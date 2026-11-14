package com.pathwayai.backend.university.catalogue;

import static org.assertj.core.api.Assertions.assertThat;

import com.pathwayai.backend.university.catalogue.UniversityCatalogue.UniversityData;
import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * Guards the real catalogue file: it must stay valid and list only universities a connector maintains (no geographic
 * targets). It reads the file only; no network and no database.
 */
class UniversityCatalogueFileTest {

    static UniversityCatalogue catalogue;

    @BeforeAll
    static void load() throws IOException {
        catalogue = UniversityCatalogue.parse(new ClassPathResource("reference-data/universities.json").getInputStream());
    }

    @Test
    void theFileIsValid() {
        assertThat(UniversityCatalogueValidator.validate(catalogue)).isEmpty();
    }

    @Test
    void theCatalogueHoldsOnlyUniversitiesThatAConnectorMaintains() throws IOException {
        // an institution is in the catalogue only because a connector supplies its programs; no geographic targets
        Set<String> connectorBacked;
        try (var in = new ClassPathResource("reference-data/studyinfo.json").getInputStream()) {
            var cfg = tools.jackson.databind.json.JsonMapper.builder().build()
                    .readValue(in, com.pathwayai.backend.provider.studyinfo.StudyinfoImporter.Config.class);
            connectorBacked = cfg.universities().stream().map(com.pathwayai.backend.provider.studyinfo.StudyinfoImporter.UniversityConfig::slug)
                    .collect(Collectors.toSet());
        }
        assertThat(catalogue.universities().stream().map(UniversityData::slug).collect(Collectors.toSet()))
                .isEqualTo(connectorBacked)
                .containsExactlyInAnyOrder("university-of-helsinki", "aalto-university", "university-of-oulu");
        assertThat(catalogue.universities()).hasSize(3).allMatch(u -> u.verificationStatus().equals("VERIFIED"))
                .allMatch(u -> u.country().equals("FI"));
    }

    @Test
    void registryOnlyInstitutionsCarryNoUnreadWebsiteAndVerifiedOnesCiteTheirSite() {
        for (UniversityData u : catalogue.universities()) {
            if (u.verificationStatus().equals("REGISTRY_ONLY")) {
                assertThat(u.websiteUrl()).as(u.slug()).isNull();
                assertThat(u.admissionsUrl()).as(u.slug()).isNull();
                assertThat(u.programmeCatalogueUrl()).as(u.slug()).isNull();
            } else {
                assertThat(u.sourcesOrEmpty()).as(u.slug())
                        .anyMatch(s -> s.type().equals("UNIVERSITY_WEBSITE") && s.url().equals(u.websiteUrl()));
            }
        }
    }
}

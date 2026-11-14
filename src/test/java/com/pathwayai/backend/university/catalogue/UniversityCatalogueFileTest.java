package com.pathwayai.backend.university.catalogue;

import static org.assertj.core.api.Assertions.assertThat;

import com.pathwayai.backend.university.catalogue.UniversityCatalogue.UniversityData;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * Guards the real catalogue file: it must stay valid and keep covering every EU member state. It reads the file
 * only; no network and no database.
 */
class UniversityCatalogueFileTest {

    static final Set<String> EU = Set.of("AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "HU",
            "IE", "IT", "LV", "LT", "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE");

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
    void everyEuMemberStateHasAtLeastOneInstitution() {
        Set<String> covered = catalogue.universities().stream().map(UniversityData::country).collect(Collectors.toSet());
        assertThat(new TreeSet<>(EU)).allMatch(covered::contains);
    }

    @Test
    void chinaJapanAndSouthKoreaAreRepresented() {
        Set<String> covered = catalogue.universities().stream().map(UniversityData::country).collect(Collectors.toSet());
        assertThat(covered).contains("CN", "JP", "KR");
    }

    @Test
    void theRequestedShortlistIsCompleteAndNothingElseIsAdded() {
        // 48 from the original shortlist plus the University of Oulu, added with registry and site evidence because the
        // Studyinfo connector imports its programs (an institution is only added when a program source exists for it)
        assertThat(catalogue.universities()).hasSize(49);
        Map<String, Long> perCountry = catalogue.universities().stream()
                .collect(Collectors.groupingBy(UniversityData::country, Collectors.counting()));
        assertThat(perCountry).containsEntry("FI", 3L).containsEntry("PL", 2L).containsEntry("BG", 1L)
                .containsEntry("CN", 1L).containsEntry("JP", 1L).containsEntry("KR", 1L);
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

    @Test
    void everyEuInstitutionIsTiedToTheRegistryByAStableIdentifier() {
        List<UniversityData> eu = catalogue.universities().stream().filter(u -> EU.contains(u.country())).toList();
        assertThat(eu).hasSize(46).allMatch(
                u -> u.externalIdsOrEmpty().stream().anyMatch(id -> id.scheme().equals("SCHAC_HOME_ORG")));
    }
}

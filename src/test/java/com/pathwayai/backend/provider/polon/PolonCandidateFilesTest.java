package com.pathwayai.backend.provider.polon;

import static org.assertj.core.api.Assertions.assertThat;

import com.pathwayai.backend.university.catalogue.UniversityCatalogue;
import com.pathwayai.backend.university.catalogue.UniversityCatalogueValidator;
import java.io.IOException;
import java.net.URI;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** Guards the Poland files without network or database: identifiers, official domains and the university catalogue. */
class PolonCandidateFilesTest {
    static final PolonSources SOURCES = new PolonSources();

    @Test
    void twentyCandidatesTenPerEntryLevelAllWithPolOnIdentifiers() {
        var all = SOURCES.candidates();
        assertThat(all).hasSize(20);
        assertThat(all.stream().filter(c -> c.entryLevel().equals("BACHELOR"))).hasSize(10);
        assertThat(all.stream().filter(c -> c.entryLevel().equals("MASTER"))).hasSize(10);
        assertThat(all).allSatisfy(c -> {
            assertThat(c.courseUuid()).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
            assertThat(c.courseInstanceUuid()).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        });
    }

    @Test
    void everyUniversityPageIsHttpsOnTheUniversitysOwnRegisteredDomain() {
        for (var c : SOURCES.candidates()) {
            for (String url : new String[] {c.programUrl(), c.feesUrl()}) {
                if (url == null) continue;
                var uri = URI.create(url);
                assertThat(uri.getScheme()).as(url).isEqualTo("https");
                assertThat(uri.getHost()).as(url).matches("(.+\\.)?" + c.officialDomain().replace(".", "\\."));
            }
        }
    }

    @Test
    void unconfirmedIdentitiesAndTheDeadProgrammePageAreExactlyWhatWasReviewed() {
        assertThat(SOURCES.candidates().stream().filter(c -> !c.identityConfirmed()).map(PolonSources.Candidate::key).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder("politechnika-informatyka-i-systemy-informacyjne-bachelor", "politechnika-informatyka-bachelor",
                        "szkola-globalny-biznes-finanse-i-zarz-dzanie-governance-bachelor", "politechnika-informatyka-master",
                        "politechnika-in-ynieria-i-analiza-danych-master", "szkola-analiza-danych-big-data-master");
        assertThat(SOURCES.candidates().stream().filter(c -> c.programUrl() == null).map(PolonSources.Candidate::key))
                .containsExactly("politechnika-sztuczna-inteligencja-artificial-intelligence-bachelor"); // put.poznan.pl/en/node/4294 returned 404 on 2026-11-20
    }

    @Test
    void uekPagesAreUnsupportedBecauseTheServerResetsRepeatedReads() {
        var uek = SOURCES.candidates().stream().filter(c -> c.university().equals("uniwersytet-ekonomiczny-w-krakowie")).toList();
        assertThat(uek).hasSize(3).allMatch(c -> c.pageMatch() == null);
        assertThat(SOURCES.candidates().stream().filter(c -> c.pageMatch() == null && c.identityConfirmed()).count()).isEqualTo(3);
    }

    @Test
    void thePolishUniversityCatalogueIsValidVerifiedAndMatchesTheCandidates() throws IOException {
        var catalogue = UniversityCatalogue.parse(new ClassPathResource("reference-data/universities-poland.json").getInputStream());
        assertThat(UniversityCatalogueValidator.validate(catalogue)).isEmpty();
        assertThat(catalogue.universities()).hasSize(8).allMatch(u -> u.country().equals("PL") && u.verificationStatus().equals("VERIFIED"));
        Set<String> slugs = catalogue.universities().stream().map(u -> u.slug()).collect(Collectors.toSet());
        assertThat(SOURCES.candidates().stream().map(PolonSources.Candidate::university).collect(Collectors.toSet())).isEqualTo(slugs);
        for (var u : catalogue.universities()) {
            var domain = SOURCES.candidates().stream().filter(c -> c.university().equals(u.slug())).findFirst().orElseThrow().officialDomain();
            assertThat(u.externalIds()).anyMatch(e -> e.scheme().equals("SCHAC_HOME_ORG") && e.value().equals(domain));
        }
    }

    @Test
    void theFinlandCatalogueFileIsUntouched() throws IOException {
        var finland = UniversityCatalogue.parse(new ClassPathResource("reference-data/universities.json").getInputStream());
        assertThat(finland.universities()).hasSize(7).allMatch(u -> u.country().equals("FI"));
    }
}

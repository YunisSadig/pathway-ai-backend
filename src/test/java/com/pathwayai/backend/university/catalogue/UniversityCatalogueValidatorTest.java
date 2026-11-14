package com.pathwayai.backend.university.catalogue;

import static org.assertj.core.api.Assertions.assertThat;

import com.pathwayai.backend.university.catalogue.UniversityCatalogue.CountryData;
import com.pathwayai.backend.university.catalogue.UniversityCatalogue.ExternalId;
import com.pathwayai.backend.university.catalogue.UniversityCatalogue.SourceData;
import com.pathwayai.backend.university.catalogue.UniversityCatalogue.UniversityData;
import java.time.LocalDate;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/** Synthetic catalogues only. */
class UniversityCatalogueValidatorTest {

    static final LocalDate DAY = LocalDate.of(2026, 1, 2);

    static UniversityData verified(String slug) {
        String site = "https://www." + slug + ".example/";
        return new UniversityData(slug, "Synthetic " + slug, "XA", "Testburg", null, site, null, null,
                List.of(new ExternalId("SCHAC_HOME_ORG", slug + ".example")), List.of(), "VERIFIED", DAY,
                List.of(new SourceData(site, "Home", "Synthetic", "UNIVERSITY_WEBSITE", DAY)));
    }

    static UniversityCatalogue of(UniversityData... universities) {
        return new UniversityCatalogue(1, "2026-01-02", List.of(new CountryData("XA", "Synthland")), List.of(universities));
    }

    static List<String> errorsFor(UnaryOperator<UniversityData> change) {
        return UniversityCatalogueValidator.validate(of(change.apply(verified("alpha"))));
    }

    @Test
    void aWellFormedCatalogueHasNoErrors() {
        assertThat(UniversityCatalogueValidator.validate(of(verified("alpha"), verified("beta")))).isEmpty();
    }

    @Test
    void rejectsBadSlugsAndDuplicates() {
        assertThat(errorsFor(u -> new UniversityData("Bad_Slug", u.name(), u.country(), null, null, u.websiteUrl(), null,
                null, List.of(), List.of(), "VERIFIED", DAY, u.sourcesOrEmpty()))).anyMatch(e -> e.contains("slug"));
        // the "--" separator of the public program identifier relies on slugs never containing it
        assertThat(errorsFor(u -> new UniversityData("a--b", u.name(), u.country(), null, null, u.websiteUrl(), null,
                null, List.of(), List.of(), "VERIFIED", DAY, u.sourcesOrEmpty()))).anyMatch(e -> e.contains("slug"));
        assertThat(UniversityCatalogueValidator.validate(of(verified("alpha"), verified("alpha"))))
                .anyMatch(e -> e.contains("duplicate slug"));
    }

    @Test
    void rejectsAnExternalIdSharedByTwoInstitutions() {
        UniversityData a = verified("alpha");
        UniversityData b = new UniversityData("beta", "Synthetic beta", "XA", null, null, "https://www.beta.example/", null,
                null, List.of(new ExternalId("SCHAC_HOME_ORG", "alpha.example")), List.of(), "VERIFIED", DAY,
                List.of(new SourceData("https://www.beta.example/", "Home", "Synthetic", "UNIVERSITY_WEBSITE", DAY)));
        assertThat(UniversityCatalogueValidator.validate(of(a, b))).anyMatch(e -> e.contains("also used by"));
    }

    @Test
    void aRegistryOnlyInstitutionMayNotCarryAWebsiteItNeverRead() {
        assertThat(errorsFor(u -> new UniversityData(u.slug(), u.name(), u.country(), null, null, u.websiteUrl(), null,
                null, List.of(), List.of(), "REGISTRY_ONLY", DAY, List.of()))).anyMatch(e -> e.contains("REGISTRY_ONLY"));
        assertThat(errorsFor(u -> new UniversityData(u.slug(), u.name(), u.country(), null, null, null, null, null,
                List.of(), List.of(), "REGISTRY_ONLY", DAY, List.of()))).isEmpty();
    }

    @Test
    void aVerifiedInstitutionMustCiteTheSiteItWasReadFrom() {
        assertThat(errorsFor(u -> new UniversityData(u.slug(), u.name(), u.country(), null, null, u.websiteUrl(), null,
                null, List.of(), List.of(), "VERIFIED", DAY, List.of()))).anyMatch(e -> e.contains("UNIVERSITY_WEBSITE source"));
        assertThat(errorsFor(u -> new UniversityData(u.slug(), u.name(), u.country(), null, null, null, null, null,
                List.of(), List.of(), "VERIFIED", DAY, List.of()))).anyMatch(e -> e.contains("websiteUrl"));
    }

    @Test
    void rejectsUnknownStatusInsecureUrlsAndUndeclaredCountries() {
        assertThat(errorsFor(u -> new UniversityData(u.slug(), u.name(), u.country(), null, null, "http://x.example", null,
                null, List.of(), List.of(), "MAYBE", DAY, List.of()))).anyMatch(e -> e.contains("https"))
                .anyMatch(e -> e.contains("verificationStatus"));
        assertThat(errorsFor(u -> new UniversityData(u.slug(), u.name(), "ZZ", null, null, u.websiteUrl(), null, null,
                List.of(), List.of(), "VERIFIED", DAY, u.sourcesOrEmpty()))).anyMatch(e -> e.contains("not declared"));
    }

    @Test
    void reportsEveryProblemNotJustTheFirst() {
        var errors = UniversityCatalogueValidator.validate(new UniversityCatalogue(2, "x",
                List.of(new CountryData("x", " ")), List.of()));
        assertThat(errors).hasSizeGreaterThanOrEqualTo(3);
    }
}

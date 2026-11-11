package com.pathwayai.backend.university.catalogue;

import java.time.LocalDate;
import java.util.List;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.io.InputStream;

/**
 * The institution catalogue file ({@code reference-data/universities.json}). Identity facts only: no tuition,
 * requirement or deadline lives here. Every value was read from a cited page; unknown stays {@code null}.
 */
public record UniversityCatalogue(int schemaVersion, String retrievedOn, List<CountryData> countries,
                                  List<UniversityData> universities) {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public record CountryData(String iso2, String name) {
    }

    public record ExternalId(String scheme, String value) {
    }

    public record SourceData(String url, String title, String publisher, String type, LocalDate retrievedOn) {
    }

    public record UniversityData(String slug, String name, String country, String city, String nativeName,
                                 String websiteUrl, String admissionsUrl, String programmeCatalogueUrl,
                                 List<ExternalId> externalIds, List<String> aliases, String verificationStatus,
                                 LocalDate verifiedOn, List<SourceData> sources) {
        public List<ExternalId> externalIdsOrEmpty() {
            return externalIds == null ? List.of() : externalIds;
        }

        public List<String> aliasesOrEmpty() {
            return aliases == null ? List.of() : aliases;
        }

        public List<SourceData> sourcesOrEmpty() {
            return sources == null ? List.of() : sources;
        }
    }

    public static UniversityCatalogue parse(InputStream in) {
        try {
            return MAPPER.readValue(in, UniversityCatalogue.class);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Catalogue file is not valid: " + e.getMessage(), e);
        }
    }
}

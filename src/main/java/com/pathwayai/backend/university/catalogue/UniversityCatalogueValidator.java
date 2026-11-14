package com.pathwayai.backend.university.catalogue;

import com.pathwayai.backend.source.entity.SourceType;
import com.pathwayai.backend.university.catalogue.UniversityCatalogue.CountryData;
import com.pathwayai.backend.university.catalogue.UniversityCatalogue.ExternalId;
import com.pathwayai.backend.university.catalogue.UniversityCatalogue.SourceData;
import com.pathwayai.backend.university.catalogue.UniversityCatalogue.UniversityData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates the whole catalogue before anything is written, so a bad file is rejected completely rather than
 * half-applied. Returns every problem, not just the first.
 */
public final class UniversityCatalogueValidator {

    public static final String VERIFIED = "VERIFIED";
    public static final String REGISTRY_ONLY = "REGISTRY_ONLY";

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");
    private static final Pattern ISO2 = Pattern.compile("^[A-Z]{2}$");
    private static final Pattern SCHEME = Pattern.compile("^[A-Z0-9_]+$");

    private UniversityCatalogueValidator() {
    }

    public static List<String> validate(UniversityCatalogue c) {
        List<String> errors = new ArrayList<>();
        if (c.schemaVersion() != 1) {
            errors.add("schemaVersion must be 1");
        }
        Set<String> isoCodes = new HashSet<>();
        Set<String> countryNames = new HashSet<>();
        for (CountryData country : nullSafe(c.countries())) {
            if (country.iso2() == null || !ISO2.matcher(country.iso2()).matches()) {
                errors.add("country iso2 must be two upper-case letters: " + country.iso2());
            } else if (!isoCodes.add(country.iso2())) {
                errors.add("duplicate country " + country.iso2());
            }
            if (blank(country.name())) {
                errors.add("country " + country.iso2() + " has no name");
            } else if (!countryNames.add(country.name())) {
                errors.add("duplicate country name " + country.name());
            }
        }
        if (nullSafe(c.universities()).isEmpty()) {
            errors.add("no universities");
        }
        Set<String> slugs = new HashSet<>();
        Map<String, String> externalOwners = new HashMap<>();
        for (UniversityData u : nullSafe(c.universities())) {
            String who = "university " + u.slug() + ": ";
            if (u.slug() == null || !SLUG.matcher(u.slug()).matches()) {
                errors.add(who + "slug must be lower-case words joined by single hyphens");
            } else if (!slugs.add(u.slug())) {
                errors.add(who + "duplicate slug");
            }
            if (blank(u.name()) || u.name().length() > 250) {
                errors.add(who + "name is required (max 250)");
            }
            if (u.country() == null || !isoCodes.contains(u.country())) {
                errors.add(who + "country " + u.country() + " is not declared in countries");
            }
            for (String url : new String[]{u.websiteUrl(), u.admissionsUrl(), u.programmeCatalogueUrl()}) {
                if (url != null && !url.startsWith("https://")) {
                    errors.add(who + "URLs must be https: " + url);
                }
            }
            String status = u.verificationStatus();
            if (!VERIFIED.equals(status) && !REGISTRY_ONLY.equals(status)) {
                errors.add(who + "verificationStatus must be VERIFIED or REGISTRY_ONLY");
            }
            if (u.verifiedOn() == null) {
                errors.add(who + "verifiedOn is required");
            }
            if (REGISTRY_ONLY.equals(status)
                    && (u.websiteUrl() != null || u.admissionsUrl() != null || u.programmeCatalogueUrl() != null)) {
                errors.add(who + "a REGISTRY_ONLY institution must not carry website URLs (they were not read)");
            }
            if (VERIFIED.equals(status)) {
                if (u.websiteUrl() == null) {
                    errors.add(who + "a VERIFIED institution needs a websiteUrl");
                } else if (u.sourcesOrEmpty().stream().noneMatch(
                        s -> "UNIVERSITY_WEBSITE".equals(s.type()) && u.websiteUrl().equals(s.url()))) {
                    errors.add(who + "a VERIFIED institution needs a UNIVERSITY_WEBSITE source for its websiteUrl");
                }
            }
            for (ExternalId id : u.externalIdsOrEmpty()) {
                if (id.scheme() == null || !SCHEME.matcher(id.scheme()).matches() || blank(id.value())) {
                    errors.add(who + "invalid external id " + id);
                    continue;
                }
                String key = id.scheme() + ":" + id.value();
                String previous = externalOwners.putIfAbsent(key, u.slug());
                if (previous != null && !previous.equals(u.slug())) {
                    errors.add(who + "external id " + key + " is also used by " + previous);
                }
            }
            for (String alias : u.aliasesOrEmpty()) {
                if (blank(alias) || alias.length() > 300) {
                    errors.add(who + "blank or over-long alias");
                }
            }
            for (SourceData s : u.sourcesOrEmpty()) {
                if (s.url() == null || !s.url().startsWith("https://")) {
                    errors.add(who + "source url must be https: " + s.url());
                }
                if (blank(s.title()) || s.retrievedOn() == null) {
                    errors.add(who + "source needs a title and retrievedOn: " + s.url());
                }
                try {
                    SourceType.valueOf(s.type());
                } catch (RuntimeException e) {
                    errors.add(who + "unknown source type " + s.type());
                }
            }
        }
        return errors;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }
}

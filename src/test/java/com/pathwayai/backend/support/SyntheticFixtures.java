package com.pathwayai.backend.support;

import com.pathwayai.backend.country.entity.Country;
import com.pathwayai.backend.program.entity.DegreeLevel;
import com.pathwayai.backend.program.entity.DegreeProgram;
import com.pathwayai.backend.source.entity.Source;
import com.pathwayai.backend.source.entity.SourceType;
import com.pathwayai.backend.university.entity.University;
import java.time.LocalDate;

/**
 * Clearly fictional data for tests only. ISO codes XA/XB are user-assigned (never real countries);
 * nothing here represents a real university, requirement or fee.
 */
public final class SyntheticFixtures {

    private SyntheticFixtures() {
    }

    public static Country countryA() {
        return new Country("XA", "Synthetic Land A");
    }

    public static Country countryB() {
        return new Country("XB", "Synthetic Land B");
    }

    public static University university(Country country, String slug, String name, String city) {
        return new University(country, slug, name, city, "https://example.invalid/" + slug,
                "Synthetic test university.");
    }

    public static DegreeProgram program(University university, String slug, String name) {
        return new DegreeProgram(university, slug, name, DegreeLevel.BACHELOR, "Synthetic Studies", 36, "English");
    }

    public static Source source(String path) {
        return new Source("https://example.invalid/" + path, "Synthetic source " + path, "Synthetic Publisher",
                SourceType.OTHER, LocalDate.of(2026, 1, 1));
    }
}

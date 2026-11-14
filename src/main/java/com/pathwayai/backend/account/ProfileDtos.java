package com.pathwayai.backend.account;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The student's own information. Every field is optional and none of it is ever turned into a score by this module. The structure is
 * normalised (qualifications and destinations are rows, not text) so that a future matching service can query it without a migration of
 * the account tables.
 */
public final class ProfileDtos {

    private ProfileDtos() {
    }

    public enum CurrentLevel { SECONDARY, BACHELOR, MASTER, DOCTORATE, OTHER }

    public enum TargetLevel { BACHELOR, MASTER, DOCTORATE }

    public enum IntakeSeason { SPRING, SUMMER, AUTUMN, WINTER }

    public enum LanguageTest { IELTS, TOEFL_IBT, PTE, DUOLINGO, CAMBRIDGE, OTHER }

    public record LanguageQualification(@NotNull LanguageTest test, @Size(max = 60) String testName,
                                        @NotNull @DecimalMin("0") @DecimalMax("1000") BigDecimal overall) {
    }

    public record ProfileRequest(
            @Pattern(regexp = "^[A-Z]{2}$", message = "Use a two-letter country code") String currentCountry,
            @Size(max = 160) String currentUniversity,
            CurrentLevel currentDegreeLevel,
            @Size(max = 120) String fieldOfStudy,
            @DecimalMin("0") @DecimalMax("1000") BigDecimal gpa,
            @DecimalMin("0.01") @DecimalMax("1000") BigDecimal gpaScaleMax,
            TargetLevel targetDegreeLevel,
            IntakeSeason intakeSeason,
            @Min(2024) @Max(2045) Integer intakeYear,
            @DecimalMin("0") @DecimalMax("10000000") BigDecimal maxAnnualTuition,
            @Pattern(regexp = "^[A-Z]{3}$", message = "Use a three-letter currency code") String maxAnnualTuitionCurrency,
            @Size(max = 5) @Valid List<LanguageQualification> languageQualifications,
            @Size(max = 10) List<@Pattern(regexp = "^[A-Z]{2}$", message = "Use a two-letter country code") String> preferredDestinations) {
    }

    public record ProfileResponse(String currentCountry, String currentUniversity, CurrentLevel currentDegreeLevel, String fieldOfStudy,
                                  BigDecimal gpa, BigDecimal gpaScaleMax, TargetLevel targetDegreeLevel, IntakeSeason intakeSeason,
                                  Integer intakeYear, BigDecimal maxAnnualTuition, String maxAnnualTuitionCurrency,
                                  List<LanguageQualification> languageQualifications, List<String> preferredDestinations,
                                  Instant updatedAt) {
    }

    public record DisplayNameRequest(@Size(max = 80) String displayName) {
    }
}

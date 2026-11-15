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
import java.time.LocalDate;
import java.util.List;

/**
 * Student profile v2 (guided onboarding). Everything is optional while the student is still filling it in; the stricter, context-aware
 * completion rules live in {@link StudentProfileRules}. Grades are stored exactly as printed on a NAMED scale: nothing here converts a grade,
 * estimates an equivalence or produces a score.
 */
public final class StudentProfileDtos {

    private StudentProfileDtos() {
    }

    public enum EducationStatus { HIGH_SCHOOL, BACHELOR, MASTER, GRADUATED }

    public enum Qualification { BACHELOR, MASTER }

    public enum StudyArea { CS_IT, ECON_BUSINESS }

    public enum HasOrNone { NONE, HAS }

    public enum ExamsStatus { NOT_TAKEN, TAKEN }

    public enum BudgetStatus { AMOUNT, NOT_SURE }

    public enum GradeStatus { ENTERED, NOT_AVAILABLE_YET }

    public enum GradeScale { FIVE_POINT, TEN_POINT, PERCENT_100, GPA_4, LETTER, OTHER }

    public enum Exam { IELTS, TOEFL, SAT }

    public enum TargetLevel { BACHELOR, MASTER }

    public enum IntakeSeason { SPRING, SUMMER, AUTUMN, WINTER }

    /** A grade as the transcript prints it. {@code value} for numeric scales, {@code letter} for LETTER, and a stated label and range for OTHER. */
    public record Grade(@NotNull GradeStatus status, GradeScale scale, @DecimalMin("-1000000") @DecimalMax("1000000") BigDecimal value,
                        @Size(max = 3) String letter, @Size(max = 60) String scaleLabel,
                        @DecimalMin("-1000000") @DecimalMax("1000000") BigDecimal scaleMin,
                        @DecimalMin("-1000000") @DecimalMax("1000000") BigDecimal scaleMax) {
    }

    public record Education(@Size(max = 160) String institution, @Pattern(regexp = "^[A-Z]{2}$") String country,
                            @Size(max = 160) String program, @Min(1) @Max(12) Integer currentYear,
                            @Min(1990) @Max(2045) Integer graduationYear, @Valid Grade grade) {
    }

    /** {@code code} names a curated skill; a custom skill has no code and a {@code label}. */
    public record Skill(@Pattern(regexp = "^[a-z0-9_]{1,40}$") String code, @Size(max = 40) String label) {
    }

    public record Experience(@NotNull @Size(min = 1, max = 120) String organization, @NotNull @Size(min = 1, max = 120) String role,
                             @Size(max = 60) String field, @Pattern(regexp = "^\\d{4}-(0[1-9]|1[0-2])$") String startMonth,
                             @Pattern(regexp = "^\\d{4}-(0[1-9]|1[0-2])$") String endMonth, Boolean ongoing,
                             @Size(max = 1000) String description) {
    }

    public record ExamSection(@NotNull String section, @NotNull @DecimalMin("0") @DecimalMax("800") BigDecimal score) {
    }

    public record ExamResult(@NotNull Exam exam, String variant, @NotNull LocalDate testDate,
                             @NotNull @DecimalMin("0") @DecimalMax("1600") BigDecimal overall,
                             @Size(max = 4) @Valid List<ExamSection> sections) {
    }

    public record Budget(BudgetStatus status, @DecimalMin("0") @DecimalMax("10000000000") BigDecimal amount,
                         @Pattern(regexp = "^[A-Z]{3}$") String currency) {
    }

    public record Onboarding(@Pattern(regexp = "^(NOT_STARTED|IN_PROGRESS|COMPLETED)$") String status, @Min(0) @Max(6) Integer step) {
    }

    /** The document the wizard reads and writes. */
    public record StudentProfile(
            @Pattern(regexp = "^[A-Z]{2}$") String citizenship,
            @Pattern(regexp = "^[A-Z]{2}$") String residence,
            EducationStatus educationStatus,
            Qualification completedQualification,
            @Valid Education school, @Valid Education bachelor, @Valid Education master,
            StudyArea studyArea, @Pattern(regexp = "^[a-z0-9_]{1,40}$") String specialization,
            @Size(max = 40) @Valid List<Skill> skills,
            HasOrNone internshipsStatus, @Size(max = 10) @Valid List<Experience> internships,
            HasOrNone volunteeringStatus, @Size(max = 10) @Valid List<Experience> volunteering,
            ExamsStatus examsStatus, @Size(max = 12) @Valid List<ExamResult> exams,
            TargetLevel targetDegreeLevel, IntakeSeason intakeSeason, @Min(2024) @Max(2045) Integer intakeYear,
            @Size(max = 10) List<@Pattern(regexp = "^[A-Z]{2}$") String> destinations,
            @Size(max = 20) List<@Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$") String> preferredUniversities,
            @Valid Budget budget,
            @Pattern(regexp = "^(en|ru)$") String language,
            @Valid Onboarding onboarding) {
    }

    public record AvatarInfo(boolean present, int version) {
    }

    /** What is still missing for a complete profile, as stable codes (the client translates them). */
    public record Completeness(boolean complete, List<String> missing) {
    }

    /** The V9 values the student entered before guided onboarding. Shown, never converted or reused as a new-model value. */
    public record Legacy(BigDecimal gpa, BigDecimal gpaScaleMax, String currentUniversity, String fieldOfStudy,
                         List<ProfileDtos.LanguageQualification> languageQualifications) {
    }

    public record StudentProfileView(StudentProfile profile, AvatarInfo avatar, Completeness completeness, Legacy legacy, Instant updatedAt) {
    }

    // --- reference data the wizard needs ---

    public record CountryOption(String code, String defaultCurrency) {
    }

    public record SkillOption(String code, String group) {
    }

    public record SpecializationOption(String code, StudyArea area) {
    }

    public record ScaleOption(GradeScale scale, String min, String max, boolean letters) {
    }

    public record Options(List<CountryOption> countries, List<ScaleOption> scales, List<String> letters,
                          List<SpecializationOption> specializations, List<SkillOption> skills, List<String> currencies,
                          List<String> commonCurrencies, List<String> languages, String toeflNewScaleEffectiveFrom) {
    }

    public record DestinationCountry(String code, String name) {
    }

    public record DestinationUniversity(String slug, String name, String countryCode) {
    }

    /** {@code restricted} means this account may not see the protected catalogue: nothing from it is returned. */
    public record Destinations(boolean restricted, List<DestinationCountry> countries, List<DestinationUniversity> universities) {
    }
}

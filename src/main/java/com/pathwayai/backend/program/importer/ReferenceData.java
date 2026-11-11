package com.pathwayai.backend.program.importer;

import com.pathwayai.backend.program.entity.DegreeLevel;
import com.pathwayai.backend.program.entity.StudyMode;
import com.pathwayai.backend.program.entity.VerificationStatus;
import com.pathwayai.backend.requirement.entity.RequirementType;
import com.pathwayai.backend.source.entity.SourceType;
import com.pathwayai.backend.tuition.entity.BillingPeriod;
import com.pathwayai.backend.tuition.entity.StudentCategory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Shape of a reference-data JSON file. Unknown properties are rejected, so a typo can never silently
 * drop a fact. Optional values are simply omitted: unknown stays unknown.
 *
 * <p>Year rule: an academic year, cycle or date range is recorded ONLY when the cited source states it. Never
 * copy a year from an adjacent heading or from the date the page was read.
 */
public record ReferenceData(
        int schemaVersion,
        List<CountryData> countries,
        List<FieldData> fields,
        List<SourceData> sources,
        List<UniversityData> universities,
        List<ProgramData> programs,
        List<RetractionData> retractions) {

    public static final int SUPPORTED_SCHEMA_VERSION = 1;

    /** Omitted retractions mean "none". */
    public ReferenceData {
        retractions = retractions == null ? List.of() : retractions;
    }

    public record CountryData(String iso2, String name) {
    }

    /** {@code parent} is the slug of another field; omit for a top-level field. */
    public record FieldData(String slug, String name, String parent) {
    }

    public record SourceData(String url, String title, String publisher, SourceType type, LocalDate retrievedOn) {
    }

    public record UniversityData(String slug, String country, String name, String city, String websiteUrl,
                                 String description) {
    }

    /**
     * {@code officialAdmissionsUrl}: an official admissions-information page that was read.
     * {@code officialApplicationUrl} + {@code officialApplicationCycle}: an official application page verified for
     * that cycle (for example "2027"). Omit both unless the application page itself was read.
     */
    public record ProgramData(
            String university,
            String slug,
            String name,
            DegreeLevel degreeLevel,
            String field,
            String campusCity,
            StudyMode studyMode,
            Integer durationMonths,
            String teachingLanguage,
            List<String> languages,
            String officialProgramUrl,
            String officialApplicationUrl,
            VerificationData verification,
            List<String> sources,
            List<IntakeData> intakes,
            List<TuitionData> tuition,
            List<RequirementData> requirements,
            String officialAdmissionsUrl,
            String officialApplicationCycle) {

        /** Omitted lists mean "nothing known", so they are normalised to empty rather than null. */
        public ProgramData {
            languages = languages == null ? List.of() : languages;
            sources = sources == null ? List.of() : sources;
            intakes = intakes == null ? List.of() : intakes;
            tuition = tuition == null ? List.of() : tuition;
            requirements = requirements == null ? List.of() : requirements;
        }
    }

    public record VerificationData(VerificationStatus status, LocalDate verifiedOn) {
    }

    public record IntakeData(String label, String academicYear, LocalDate startDate, LocalDate applicationDeadline,
                             StudentCategory applicantCategory, String source) {
    }

    /**
     * {@code academicYear} only when the source prints one (for example "2026/2027"); otherwise a start-date range
     * ({@code effectiveFrom}/{@code effectiveTo}) exactly as the source words it, or neither (period unknown).
     * {@code scopeNote} is the source's wording of that scope.
     */
    public record TuitionData(String academicYear, StudentCategory studentCategory, BigDecimal amount,
                              String currency, BillingPeriod billingPeriod, String source,
                              LocalDate effectiveFrom, LocalDate effectiveTo, String scopeNote) {
    }

    public record RequirementData(String applicationCycle, RequirementType type, String description,
                                  BigDecimal minimumValue, String valueUnit, String source) {
    }

    public enum RetractionType {
        TUITION, INTAKE
    }

    /**
     * An audited removal of a fact that was published in error (for example a year that was inferred rather than
     * stated). The importer never deletes anything else. {@code program} is the identifier
     * {@code <university-slug>--<program-slug>}; a retraction of something that is already gone does nothing, so it
     * is safe to run again. Tuition is identified by (academicYear, studentCategory); an intake by
     * (label, academicYear, applicantCategory).
     */
    public record RetractionData(RetractionType type, String program, String academicYear,
                                 StudentCategory studentCategory, String label, StudentCategory applicantCategory,
                                 String reason) {
    }
}

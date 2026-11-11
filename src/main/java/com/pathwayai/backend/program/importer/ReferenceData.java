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
 */
public record ReferenceData(
        int schemaVersion,
        List<CountryData> countries,
        List<FieldData> fields,
        List<SourceData> sources,
        List<UniversityData> universities,
        List<ProgramData> programs) {

    public static final int SUPPORTED_SCHEMA_VERSION = 1;

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
            List<RequirementData> requirements) {

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

    public record TuitionData(String academicYear, StudentCategory studentCategory, BigDecimal amount,
                              String currency, BillingPeriod billingPeriod, String source) {
    }

    public record RequirementData(String applicationCycle, RequirementType type, String description,
                                  BigDecimal minimumValue, String valueUnit, String source) {
    }
}

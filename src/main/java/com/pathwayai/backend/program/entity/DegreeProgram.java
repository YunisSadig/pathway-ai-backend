package com.pathwayai.backend.program.entity;

import com.pathwayai.backend.field.entity.AcademicField;
import com.pathwayai.backend.source.entity.Source;
import com.pathwayai.backend.university.entity.University;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;
import org.hibernate.annotations.BatchSize;

/**
 * A degree program offered by a university. Program-specific facts live here (or in intake, tuition and
 * requirement rows), never on the university. Every optional value is null when unknown.
 */
@Entity
@Table(name = "degree_program")
public class DegreeProgram {

    /** Separates the university slug from the program slug in {@link #getIdentifier()}. */
    public static final String IDENTIFIER_SEPARATOR = "--";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "university_id", nullable = false)
    private University university;

    @Column(nullable = false, length = 120)
    private String slug;

    @Column(nullable = false, length = 250)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "degree_level", nullable = false, length = 20)
    private DegreeLevel degreeLevel;

    /** Legacy free-text subject from V1. The academic field is the normalised source of truth. */
    @Column(name = "field_of_study", length = 150)
    private String fieldOfStudy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "academic_field_id")
    private AcademicField academicField;

    @Column(name = "duration_months")
    private Integer durationMonths;

    /** Display text of the main teaching language (V1 column, kept for the existing API). */
    @Column(name = "teaching_language", length = 60)
    private String teachingLanguage;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "program_language", joinColumns = @JoinColumn(name = "program_id"))
    @Column(name = "language_code", nullable = false, length = 8)
    @BatchSize(size = 100)
    private Set<String> teachingLanguageCodes = new LinkedHashSet<>();

    @Column(name = "campus_city", length = 120)
    private String campusCity;

    @Enumerated(EnumType.STRING)
    @Column(name = "study_mode", length = 20)
    private StudyMode studyMode;

    @Column(name = "official_program_url", length = 1000)
    private String officialProgramUrl;

    /** Official admissions-information page. Informational, never presented as an Apply button. */
    @Column(name = "official_admissions_url", length = 1000)
    private String officialAdmissionsUrl;

    /** Only ever set for a VERIFIED program (database constraint), and only for {@link #officialApplicationCycle}. */
    @Column(name = "official_application_url", length = 1000)
    private String officialApplicationUrl;

    /** The cycle (e.g. "2027" or "2026/2027") the application URL was verified for. */
    @Column(name = "official_application_cycle", length = 20)
    private String officialApplicationCycle;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false, length = 20)
    private VerificationStatus verificationStatus = VerificationStatus.UNVERIFIED;

    @Column(name = "verified_on")
    private LocalDate verifiedOn;

    /** ACTIVE or DISCONTINUED; written only by the refresh pipeline (database default ACTIVE), never by this entity. */
    @Column(name = "lifecycle_status", insertable = false, updatable = false)
    private String lifecycleStatus;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "program_source",
            joinColumns = @JoinColumn(name = "program_id"),
            inverseJoinColumns = @JoinColumn(name = "source_id"))
    @BatchSize(size = 100)
    private Set<Source> sources = new LinkedHashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DegreeProgram() {
    }

    public DegreeProgram(University university, String slug, String name, DegreeLevel degreeLevel,
                         String fieldOfStudy, Integer durationMonths, String teachingLanguage) {
        this.university = university;
        this.slug = slug;
        this.name = name;
        this.degreeLevel = degreeLevel;
        this.fieldOfStudy = fieldOfStudy;
        this.durationMonths = durationMonths;
        this.teachingLanguage = teachingLanguage;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /** Applies the descriptive facts of an import. Identity (university, slug) never changes. */
    public void describe(String name, DegreeLevel degreeLevel, AcademicField academicField, String campusCity,
                         StudyMode studyMode, Integer durationMonths, String teachingLanguage,
                         Set<String> languageCodes, String officialProgramUrl, String officialAdmissionsUrl) {
        this.name = name;
        this.degreeLevel = degreeLevel;
        this.academicField = academicField;
        this.campusCity = campusCity;
        this.studyMode = studyMode;
        this.durationMonths = durationMonths;
        this.teachingLanguage = teachingLanguage;
        // Copy first: the argument may be this entity's own collection, which clear() would empty.
        Set<String> codes = new LinkedHashSet<>(languageCodes);
        this.teachingLanguageCodes.clear();
        this.teachingLanguageCodes.addAll(codes);
        this.officialProgramUrl = officialProgramUrl;
        this.officialAdmissionsUrl = officialAdmissionsUrl;
    }

    /**
     * Records the verification outcome. The application URL is only accepted when VERIFIED and when the cycle it
     * was verified for is stated.
     */
    public void verify(VerificationStatus status, LocalDate verifiedOn, String officialApplicationUrl,
                       String officialApplicationCycle) {
        if (officialApplicationUrl != null && status != VerificationStatus.VERIFIED) {
            throw new IllegalArgumentException("An application URL requires a VERIFIED program");
        }
        if (officialApplicationUrl != null && (officialApplicationCycle == null || officialApplicationCycle.isBlank())) {
            throw new IllegalArgumentException("An application URL requires the cycle it was verified for");
        }
        if (status == VerificationStatus.VERIFIED && verifiedOn == null) {
            throw new IllegalArgumentException("A VERIFIED program requires a verification date");
        }
        this.verificationStatus = status;
        this.verifiedOn = verifiedOn;
        this.officialApplicationUrl = officialApplicationUrl;
        this.officialApplicationCycle = officialApplicationUrl == null ? null : officialApplicationCycle;
    }

    /** Globally unique, stable public identifier: {@code <university-slug>--<program-slug>}. */
    public String getIdentifier() {
        return university.getSlug() + IDENTIFIER_SEPARATOR + slug;
    }

    public Long getId() { return id; }
    public University getUniversity() { return university; }
    public String getSlug() { return slug; }
    public String getName() { return name; }
    public DegreeLevel getDegreeLevel() { return degreeLevel; }
    public String getFieldOfStudy() { return fieldOfStudy; }
    public AcademicField getAcademicField() { return academicField; }
    public Integer getDurationMonths() { return durationMonths; }
    public String getTeachingLanguage() { return teachingLanguage; }
    public Set<String> getTeachingLanguageCodes() { return teachingLanguageCodes; }
    public String getCampusCity() { return campusCity; }
    public StudyMode getStudyMode() { return studyMode; }
    public String getOfficialProgramUrl() { return officialProgramUrl; }
    public String getOfficialAdmissionsUrl() { return officialAdmissionsUrl; }
    public String getOfficialApplicationUrl() { return officialApplicationUrl; }
    public String getOfficialApplicationCycle() { return officialApplicationCycle; }
    public VerificationStatus getVerificationStatus() { return verificationStatus; }
    public LocalDate getVerifiedOn() { return verifiedOn; }
    public String getLifecycleStatus() { return lifecycleStatus; }
    public Set<Source> getSources() { return sources; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

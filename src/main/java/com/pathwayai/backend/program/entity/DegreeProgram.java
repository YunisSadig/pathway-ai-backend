package com.pathwayai.backend.program.entity;

import com.pathwayai.backend.university.entity.University;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "degree_program")
public class DegreeProgram {

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

    @Column(name = "field_of_study", length = 150)
    private String fieldOfStudy;

    @Column(name = "duration_months")
    private Integer durationMonths;

    @Column(name = "teaching_language", length = 60)
    private String teachingLanguage;

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

    public Long getId() { return id; }
    public University getUniversity() { return university; }
    public String getSlug() { return slug; }
    public String getName() { return name; }
    public DegreeLevel getDegreeLevel() { return degreeLevel; }
    public String getFieldOfStudy() { return fieldOfStudy; }
    public Integer getDurationMonths() { return durationMonths; }
    public String getTeachingLanguage() { return teachingLanguage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

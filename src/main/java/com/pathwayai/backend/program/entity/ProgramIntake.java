package com.pathwayai.backend.program.entity;

import com.pathwayai.backend.source.entity.Source;
import com.pathwayai.backend.tuition.entity.StudentCategory;
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
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/** One intake of a program (e.g. "Winter semester") with its dates. Every date may be unknown. */
@Entity
@Table(name = "program_intake")
public class ProgramIntake {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "program_id", nullable = false)
    private DegreeProgram program;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_id", nullable = false)
    private Source source;

    @Column(nullable = false, length = 100)
    private String label;

    @Column(name = "academic_year", length = 9)
    private String academicYear;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "application_deadline")
    private LocalDate applicationDeadline;

    /** Null means the intake applies to every applicant category. */
    @Enumerated(EnumType.STRING)
    @Column(name = "applicant_category", length = 20)
    private StudentCategory applicantCategory;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ProgramIntake() {
    }

    public ProgramIntake(DegreeProgram program, Source source, String label, String academicYear,
                         LocalDate startDate, LocalDate applicationDeadline, StudentCategory applicantCategory) {
        this.program = program;
        this.source = source;
        this.label = label;
        this.academicYear = academicYear;
        this.startDate = startDate;
        this.applicationDeadline = applicationDeadline;
        this.applicantCategory = applicantCategory;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public void update(Source source, LocalDate startDate, LocalDate applicationDeadline) {
        this.source = source;
        this.startDate = startDate;
        this.applicationDeadline = applicationDeadline;
    }

    public Long getId() { return id; }
    public DegreeProgram getProgram() { return program; }
    public Source getSource() { return source; }
    public String getLabel() { return label; }
    public String getAcademicYear() { return academicYear; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getApplicationDeadline() { return applicationDeadline; }
    public StudentCategory getApplicantCategory() { return applicantCategory; }
    public Instant getCreatedAt() { return createdAt; }
}

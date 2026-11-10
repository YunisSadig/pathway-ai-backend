package com.pathwayai.backend.requirement.entity;

import com.pathwayai.backend.program.entity.DegreeProgram;
import com.pathwayai.backend.source.entity.Source;
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
import java.math.BigDecimal;
import java.time.Instant;

/** A requirement for one program in one application cycle, always tied to a source. */
@Entity
@Table(name = "academic_requirement")
public class AcademicRequirement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "program_id", nullable = false)
    private DegreeProgram program;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_id", nullable = false)
    private Source source;

    @Column(name = "application_cycle", nullable = false, length = 20)
    private String applicationCycle;

    @Enumerated(EnumType.STRING)
    @Column(name = "requirement_type", nullable = false, length = 40)
    private RequirementType requirementType;

    @Column(nullable = false, columnDefinition = "text")
    private String description;

    @Column(name = "minimum_value", precision = 8, scale = 2)
    private BigDecimal minimumValue;

    @Column(name = "value_unit", length = 40)
    private String valueUnit;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AcademicRequirement() {
    }

    public AcademicRequirement(DegreeProgram program, Source source, String applicationCycle,
                               RequirementType requirementType, String description,
                               BigDecimal minimumValue, String valueUnit) {
        this.program = program;
        this.source = source;
        this.applicationCycle = applicationCycle;
        this.requirementType = requirementType;
        this.description = description;
        this.minimumValue = minimumValue;
        this.valueUnit = valueUnit;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public DegreeProgram getProgram() { return program; }
    public Source getSource() { return source; }
    public String getApplicationCycle() { return applicationCycle; }
    public RequirementType getRequirementType() { return requirementType; }
    public String getDescription() { return description; }
    public BigDecimal getMinimumValue() { return minimumValue; }
    public String getValueUnit() { return valueUnit; }
    public Instant getCreatedAt() { return createdAt; }
}

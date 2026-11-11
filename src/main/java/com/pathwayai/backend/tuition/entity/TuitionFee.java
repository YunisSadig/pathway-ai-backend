package com.pathwayai.backend.tuition.entity;

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
import java.time.LocalDate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Tuition for one program and student category, for the period the SOURCE says it covers: an academic year
 * (e.g. "2026/2027"), a range of study-right start dates, or neither (period unknown). The period is never
 * inferred from anything the source does not say.
 */
@Entity
@Table(name = "tuition_fee")
public class TuitionFee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "program_id", nullable = false)
    private DegreeProgram program;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_id", nullable = false)
    private Source source;

    /** Null when the source prints no academic year. */
    @Column(name = "academic_year", length = 9)
    private String academicYear;

    @Enumerated(EnumType.STRING)
    @Column(name = "student_category", nullable = false, length = 20)
    private StudentCategory studentCategory;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_period", nullable = false, length = 20)
    private BillingPeriod billingPeriod;

    /** First study-right start date this fee applies to (inclusive); null = open or not stated. */
    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    /** Last study-right start date this fee applies to (inclusive); null = open or not stated. */
    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    /** The source's own wording of the scope, kept for audit. */
    @Column(name = "scope_note", length = 300)
    private String scopeNote;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TuitionFee() {
    }

    public TuitionFee(DegreeProgram program, Source source, String academicYear, StudentCategory studentCategory,
                      BigDecimal amount, String currency, BillingPeriod billingPeriod) {
        this(program, source, academicYear, studentCategory, amount, currency, billingPeriod, null, null, null);
    }

    public TuitionFee(DegreeProgram program, Source source, String academicYear, StudentCategory studentCategory,
                      BigDecimal amount, String currency, BillingPeriod billingPeriod,
                      LocalDate effectiveFrom, LocalDate effectiveTo, String scopeNote) {
        this.program = program;
        this.source = source;
        this.academicYear = academicYear;
        this.studentCategory = studentCategory;
        this.amount = amount;
        this.currency = currency;
        this.billingPeriod = billingPeriod;
        this.effectiveFrom = effectiveFrom;
        this.effectiveTo = effectiveTo;
        this.scopeNote = scopeNote;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    /** Re-cites the same fact (same key and value) from the latest source and wording. */
    public void recite(Source source, String scopeNote) {
        this.source = source;
        this.scopeNote = scopeNote;
    }

    public Long getId() { return id; }
    public DegreeProgram getProgram() { return program; }
    public Source getSource() { return source; }
    public String getAcademicYear() { return academicYear; }
    public StudentCategory getStudentCategory() { return studentCategory; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public BillingPeriod getBillingPeriod() { return billingPeriod; }
    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public LocalDate getEffectiveTo() { return effectiveTo; }
    public String getScopeNote() { return scopeNote; }
    public Instant getCreatedAt() { return createdAt; }
}

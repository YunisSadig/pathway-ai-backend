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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Tuition for one program, academic year (e.g. "2026/2027") and student category. */
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

    @Column(name = "academic_year", nullable = false, length = 9)
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

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TuitionFee() {
    }

    public TuitionFee(DegreeProgram program, Source source, String academicYear, StudentCategory studentCategory,
                      BigDecimal amount, String currency, BillingPeriod billingPeriod) {
        this.program = program;
        this.source = source;
        this.academicYear = academicYear;
        this.studentCategory = studentCategory;
        this.amount = amount;
        this.currency = currency;
        this.billingPeriod = billingPeriod;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public DegreeProgram getProgram() { return program; }
    public Source getSource() { return source; }
    public String getAcademicYear() { return academicYear; }
    public StudentCategory getStudentCategory() { return studentCategory; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public BillingPeriod getBillingPeriod() { return billingPeriod; }
    public Instant getCreatedAt() { return createdAt; }
}

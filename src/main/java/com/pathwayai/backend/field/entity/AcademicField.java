package com.pathwayai.backend.field.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/** A subject area. Rows, not enum constants, so new disciplines never need a code change. */
@Entity
@Table(name = "academic_field")
public class AcademicField {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private AcademicField parent;

    @Column(nullable = false, length = 120)
    private String slug;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AcademicField() {
    }

    public AcademicField(AcademicField parent, String slug, String name) {
        this.parent = parent;
        this.slug = slug;
        this.name = name;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public void rename(AcademicField parent, String name) {
        this.parent = parent;
        this.name = name;
    }

    public Long getId() { return id; }
    public AcademicField getParent() { return parent; }
    public String getSlug() { return slug; }
    public String getName() { return name; }
    public Instant getCreatedAt() { return createdAt; }
}

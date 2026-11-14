package com.pathwayai.backend.source.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/** An official page or document that backs a requirement or tuition figure. */
@Entity
@Table(name = "source")
public class Source {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 1000)
    private String url;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(length = 200)
    private String publisher;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 40)
    private SourceType sourceType;

    @Column(name = "retrieved_on", nullable = false)
    private LocalDate retrievedOn;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Source() {
    }

    public Source(String url, String title, String publisher, SourceType sourceType, LocalDate retrievedOn) {
        this.url = url;
        this.title = title;
        this.publisher = publisher;
        this.sourceType = sourceType;
        this.retrievedOn = retrievedOn;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    /** Records a newer check of the same official page (the URL is the identity). */
    public void refresh(String title, String publisher, SourceType sourceType, LocalDate retrievedOn) {
        this.title = title;
        this.publisher = publisher;
        this.sourceType = sourceType;
        this.retrievedOn = retrievedOn;
    }

    public Long getId() { return id; }
    public String getUrl() { return url; }
    public String getTitle() { return title; }
    public String getPublisher() { return publisher; }
    public SourceType getSourceType() { return sourceType; }
    public LocalDate getRetrievedOn() { return retrievedOn; }
    public Instant getCreatedAt() { return createdAt; }
}

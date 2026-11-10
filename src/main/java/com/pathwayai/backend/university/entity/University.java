package com.pathwayai.backend.university.entity;

import com.pathwayai.backend.country.entity.Country;
import com.pathwayai.backend.program.entity.DegreeProgram;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "university")
public class University {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "country_id", nullable = false)
    private Country country;

    @Column(nullable = false, length = 120)
    private String slug;

    @Column(nullable = false, length = 250)
    private String name;

    @Column(length = 120)
    private String city;

    @Column(name = "website_url", length = 500)
    private String websiteUrl;

    @Column(columnDefinition = "text")
    private String description;

    @OneToMany(mappedBy = "university")
    private List<DegreeProgram> programs = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected University() {
    }

    public University(Country country, String slug, String name, String city, String websiteUrl, String description) {
        this.country = country;
        this.slug = slug;
        this.name = name;
        this.city = city;
        this.websiteUrl = websiteUrl;
        this.description = description;
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
    public Country getCountry() { return country; }
    public String getSlug() { return slug; }
    public String getName() { return name; }
    public String getCity() { return city; }
    public String getWebsiteUrl() { return websiteUrl; }
    public String getDescription() { return description; }
    public List<DegreeProgram> getPrograms() { return programs; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

package com.pathwayai.backend.program.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Immutable;

/**
 * Read-only view of the source registry used only to ask "was a source of this program read successfully
 * recently?". Never exposed in an API response; the registry is written by the refresh pipeline, not by this entity.
 */
@Entity
@Immutable
@Table(name = "source_monitor")
public class ProgramFreshness {

    @Id
    @Column(name = "source_id")
    private Long sourceId;

    @Column(name = "program_id")
    private Long programId;

    @Column(name = "last_success_at")
    private Instant lastSuccessAt;

    protected ProgramFreshness() {
    }

    public Long getSourceId() { return sourceId; }
    public Long getProgramId() { return programId; }
    public Instant getLastSuccessAt() { return lastSuccessAt; }
}

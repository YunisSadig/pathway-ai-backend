package com.pathwayai.backend.source.repository;

import com.pathwayai.backend.source.entity.Source;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SourceRepository extends JpaRepository<Source, Long> {
}

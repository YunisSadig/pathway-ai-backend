package com.pathwayai.backend.field.repository;

import com.pathwayai.backend.field.entity.AcademicField;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AcademicFieldRepository extends JpaRepository<AcademicField, Long> {

    Optional<AcademicField> findBySlug(String slug);
}

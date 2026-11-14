package com.pathwayai.backend.university.repository;

import com.pathwayai.backend.university.entity.University;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface UniversityRepository extends JpaRepository<University, Long>, JpaSpecificationExecutor<University> {

    @EntityGraph(attributePaths = {"country", "programs", "programs.academicField"})
    Optional<University> findBySlug(String slug);
}
